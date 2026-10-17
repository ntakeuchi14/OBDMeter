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

    override fun readPid(pid: Int): IntArray? {
        val base = "01%02X".format(pid)
        if (hintSupported) {
            val lines = command("$base 1")
            val data = parseMode01(lines, pid)
            if (data != null) return data
            if (lines.any { it.trim() == "?" }) {
                // 応答数指定に非対応なアダプター
                logger.log(CommLogger.Direction.INFO, "応答数ヒントに非対応のため無効化")
                hintSupported = false
            } else {
                return null
            }
        }
        return parseMode01(command(base), pid)
    }

    override fun readAdapterVoltage(): Double? {
        val line = command("ATRV").firstOrNull { it.any(Char::isDigit) } ?: return null
        return line.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
    }

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

    private fun sendRaw(cmd: String, timeoutMs: Int): String {
        logger.log(CommLogger.Direction.TX, cmd.ifEmpty { "<CR>" })
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val buf = ByteArray(64)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) {
                logger.log(CommLogger.Direction.ERROR, "タイムアウト: $cmd (受信: ${sb.toString().replace('\r', '|')})")
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
        internal fun parseMode01(lines: List<String>, pid: Int): IntArray? {
            val prefix = "41%02X".format(pid)
            for (line in lines) {
                val compact = line.replace(" ", "").uppercase()
                // "SEARCHING..." "BUS INIT: ...OK" は別行で来るので、行頭一致だけを見る
                if (!compact.startsWith(prefix)) continue
                val bytes = Hex.parse(compact)
                if (bytes.size < 3) continue
                return bytes.copyOfRange(2, bytes.size)
            }
            return null
        }
    }
}
