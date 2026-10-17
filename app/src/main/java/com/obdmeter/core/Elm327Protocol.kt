package com.obdmeter.core

/**
 * ELM327 互換アダプター (OBDLink SX / EX は STN チップで ELM327 上位互換) 用の実装。
 * テキストの AT コマンドと OBD リクエストを送り、プロンプト '>' までを 1 応答として読む。
 */
class Elm327Protocol(
    private val transport: Transport,
    private val options: Options = Options(),
    private val logger: CommLogger = CommLogger.NONE,
) : ObdProtocol {

    data class Options(
        /** ATSP の値。"0" = 自動判別。E90 なら D-CAN 車は "6" (CAN 11bit 500k)、K-Line 車は "5" (KWP 高速初期化)。 */
        val protocol: String = "0",
        /**
         * Mode 01 要求の末尾に応答数 "1" を付けて、1 応答受信で即座に返させる。
         * 更新速度が大きく上がるが、まれに非対応のクローン品もあるので設定で切れるようにする。
         */
        val singleResponseHint: Boolean = true,
        val commandTimeoutMs: Int = 2000,
        /** 最初の 0100 はプロトコル自動判別で時間がかかる。 */
        val searchTimeoutMs: Int = 15000,
    )

    private var hintSupported = options.singleResponseHint

    override fun initialize(): String {
        transport.purge()
        // 途中状態をリセットするため CR を一度送ってプロンプトを待つ
        sendRaw("", 1000)
        val ws = command("ATWS", 5000) // ウォームスタート (ボーレート設定は維持される)
        logger.log(CommLogger.Direction.INFO, "Adapter: ${ws.joinToString(" ").trim()}")
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP${options.protocol}")) {
            val r = command(cmd)
            if (r.none { it.contains("OK") }) {
                logger.log(CommLogger.Direction.ERROR, "$cmd に OK が返りません: $r")
            }
        }
        val id = command("ATI").lastOrNull { it.isNotBlank() } ?: "?"

        // 車両との接続確立 (自動判別ならここでプロトコルが決まる)
        val first = command("0100", options.searchTimeoutMs)
        checkError(first)?.let { throw ProtocolException("車両に接続できません: $it") }
        if (parseMode01(first, 0x00) == null) {
            throw ProtocolException("0100 への応答を解釈できません: $first")
        }
        val dp = command("ATDPN").lastOrNull()?.trim() ?: "?"
        val desc = command("ATDP").lastOrNull()?.trim() ?: "?"
        return "$id / $desc (ATDPN=$dp)"
    }

    override var lastFailure: String? = null
        private set

    override fun readPid(pid: Int): IntArray? {
        val base = "01%02X".format(pid)
        if (hintSupported) {
            val lines = command("$base 1")
            val data = parseMode01(lines, pid)
            if (data != null) return ok(data)
            if (lines.any { it.trim() == "?" }) {
                // 応答数指定に非対応なアダプター
                logger.log(CommLogger.Direction.INFO, "応答数ヒントに非対応のため無効化")
                hintSupported = false
            } else {
                return fail(lines)
            }
        }
        val lines = command(base)
        return parseMode01(lines, pid)?.let(::ok) ?: fail(lines)
    }

    private fun ok(data: IntArray): IntArray { lastFailure = null; return data }

    private fun fail(lines: List<String>): IntArray? {
        lastFailure = checkError(lines) ?: lines.joinToString("|").ifEmpty { "応答なし" }
        return null
    }

    /**
     * 対応 PID 一覧は複数 ECU (DME・EGS など) が返すので、応答数ヒントを付けずに全 ECU 分を受け取り合算する。
     * ヒント付きだと最初に返した 1 台分しか取れず、本当は読める項目を「非対応」と誤判定してしまう。
     */
    override fun readSupportedPids(): Set<Int> {
        val result = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xA0) {
            val lines = command("01%02X".format(base), 4000)
            val bitmaps = parseMode01All(lines, base).filter { it.size >= 4 }
            if (bitmaps.isEmpty()) break
            logger.log(CommLogger.Direction.INFO, "PID %02X: %d 台の ECU が応答".format(base, bitmaps.size))
            for (data in bitmaps) result += decodeBitmap(base, data)
            if (!result.contains(base + 0x20)) break
            base += 0x20
        }
        return result
    }

    override fun readAdapterVoltage(): Double? = parseVoltage(command("ATRV"))

    override fun close() {
        runCatching { transport.close() }
    }

    // ---------------------------------------------------------------------

    /** コマンドを送り、'>' までの応答を行単位で返す (空行・エコー行は除去)。 */
    internal fun command(cmd: String, timeoutMs: Int = options.commandTimeoutMs): List<String> {
        val raw = sendRaw(cmd, timeoutMs)
        return raw.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) }
    }

    /** 前回タイムアウトした場合、遅れて届いた応答が次の応答に混ざらないよう捨てる */
    private var needsResync = false

    private fun sendRaw(cmd: String, timeoutMs: Int): String {
        if (needsResync) {
            transport.purge()
            needsResync = false
        }
        logger.log(CommLogger.Direction.TX, cmd.ifEmpty { "<CR>" })
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val buf = ByteArray(64)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) {
                logger.log(CommLogger.Direction.ERROR, "タイムアウト: $cmd (受信: ${sb.toString().replace('\r', '|')})")
                needsResync = true
                break
            }
            val n = transport.read(buf, minOf(remaining, 100))
            if (n <= 0) continue
            for (i in 0 until n) {
                val c = (buf[i].toInt() and 0xFF).toChar()
                if (c == '>') {
                    val text = sb.toString()
                    logger.log(CommLogger.Direction.RX, text.replace('\r', '|').trim('|'))
                    return text
                }
                if (c != '\u0000') sb.append(c)
            }
        }
        return sb.toString()
    }

    companion object {
        private val ERRORS = listOf(
            "NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS INIT: ...ERROR",
            "BUS INIT: ERROR", "FB ERROR", "DATA ERROR", "STOPPED", "ERR", "LV RESET", "ACT ALERT",
        )

        internal fun checkError(lines: List<String>): String? =
            lines.firstOrNull { l -> ERRORS.any { l.uppercase().contains(it) } }

        /**
         * ヘッダー OFF・スペース OFF の応答行から "41 <pid> A B ..." を探し、データ部を返す。
         * 複数 ECU が応答した場合は最初の 1 件を使う。
         */
        private val VOLTAGE = Regex("""(\d{1,2}\.\d)\s*V""", RegexOption.IGNORE_CASE)

        /**
         * ATRV の応答 ("14.1V") を読む。"12V" 系の妥当な範囲 (5〜20V) 以外は無効扱い。
         * 0.0V などは測れていないだけなので null にして「---」表示にする。
         */
        internal fun parseVoltage(lines: List<String>): Double? =
            lines.firstNotNullOfOrNull { VOLTAGE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
                ?.takeIf { it in 5.0..20.0 }

        internal fun parseMode01(lines: List<String>, pid: Int): IntArray? =
            parseMode01All(lines, pid).firstOrNull()

        /**
         * 全 ECU 分の応答を返す。ヘッダー表示が有効なままの場合
         * ("7E8 04 41 0C 1A F8" / 29bit "18DAF110 04 41 0C ...") にも対応する。
         */
        internal fun parseMode01All(lines: List<String>, pid: Int): List<IntArray> {
            val prefix = "41%02X".format(pid)
            val result = mutableListOf<IntArray>()
            for (line in lines) {
                val compact = line.replace(" ", "").uppercase()
                if (compact.any { !(it.isDigit() || it in 'A'..'F') }) continue // "SEARCHING..." など
                // 行頭 (ヘッダーなし) か、11bit/29bit ヘッダー (+長さバイト) の直後だけを見る
                val idx = listOf(0, 3, 5, 8, 10).firstOrNull { compact.startsWith(prefix, it) } ?: continue
                val bytes = Hex.parse(compact.substring(idx))
                if (bytes.size < 3) continue
                result += bytes.copyOfRange(2, bytes.size)
            }
            return result
        }
    }
}
