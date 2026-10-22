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
        /**
         * 使いたい通信速度。アダプターは電源投入時 115200 なので、接続後にアダプターへ切り替えを指示する
         * (OBDLink は STBR、ほかの ELM327 は ATBRD)。失敗したら元の速度のまま使う
         */
        val baudRate: Int = 115200,
        /** ポートを開いたときの通信速度 */
        val initialBaud: Int = 115200,
    )

    /** 今の通信速度 */
    var currentBaud: Int = options.initialBaud
        private set

    private var hintSupported = options.singleResponseHint

    override fun initAdapter(): String {
        // 前回の接続で速度を上げたまま (ウォームスタートでは戻らない) のこともあるので、応答する速度を探す
        if (!probeBaud()) {
            throw ProtocolException("アダプターが応答しません。ケーブルを確認してください")
        }
        val ws = command("ATWS", 5000) // ウォームスタート (ボーレート設定は維持される)
        if (ws.isEmpty() && lastTimedOut) {
            throw ProtocolException("アダプターが応答しません。ボーレートの設定とケーブルを確認してください")
        }
        logger.log(CommLogger.Direction.INFO, "Adapter: ${ws.joinToString(" ").trim()}")
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP${options.protocol}")) {
            val r = command(cmd)
            if (r.none { it.contains("OK") }) {
                logger.log(CommLogger.Direction.ERROR, "$cmd に OK が返りません: $r")
            }
        }
        currentHeader = ""
        currentCea = ""
        currentFcHeader = ""
        setupDone.clear()
        if (options.baudRate != currentBaud) switchBaud(options.baudRate)
        return command("ATI").lastOrNull { it.isNotBlank() } ?: "ELM327"
    }

    /**
     * アダプターが応答する通信速度を探す (115200 → 設定の速度 → 38400 → 9600)。見つかればその速度のままにする
     */
    private fun probeBaud(): Boolean {
        val rates = listOf(options.initialBaud, options.baudRate, 115200, 38400, 9600).distinct()
        for ((i, rate) in rates.withIndex()) {
            if (rate != currentBaud) {
                if (!transport.setBaudRate(rate)) continue
                currentBaud = rate
            }
            transport.purge()
            // 途中状態をリセットするため CR を一度送ってプロンプトを待つ
            sendRaw("", if (i == 0) 1000 else 400)
            val r = command("ATI", if (i == 0) 1500 else 600)
            if (r.any { it.any(Char::isLetter) } && !lastTimedOut) {
                if (i > 0) logger.log(CommLogger.Direction.INFO, "アダプターは ${rate}bps で応答しました")
                return true
            }
        }
        return false
    }

    /**
     * アダプターの通信速度を [target] に切り替える。OBDLink (STN チップ) は STBR、ほかの ELM327 は ATBRD。
     * 手順: 切り替えの命令に OK が返る → こちらも速度を変える → CR を送る → 新しい速度でプロンプトが返れば成功。
     * 返らなければ、アダプターは待ち時間の後に元の速度へ戻るので、こちらも戻す
     */
    private fun switchBaud(target: Int) {
        val from = currentBaud
        val stn = command("STI", 1000).any { it.contains("STN", ignoreCase = true) }
        val cmd = if (stn) {
            command("STBRT 500") // 新しい速度での CR を待つ時間 (ms)
            "STBR $target"
        } else {
            command("ATBRT 64") // 待ち時間 100 × 5ms
            val div = Math.round(4_000_000.0 / target).toInt().coerceIn(8, 0xFF)
            "ATBRD %02X".format(div)
        }
        logger.log(CommLogger.Direction.TX, cmd)
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        // OK (切り替える) か ? (非対応) を待つ
        val reply = StringBuilder()
        val buf = ByteArray(64)
        val deadline = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < deadline && !reply.contains("OK") && !reply.contains("?")) {
            val n = transport.read(buf, 50)
            for (i in 0 until n) reply.append((buf[i].toInt() and 0xFF).toChar())
        }
        logger.log(CommLogger.Direction.RX, reply.toString().replace('\r', '|').trim('|'))
        if (!reply.contains("OK")) {
            logger.log(CommLogger.Direction.INFO, "アダプターが速度の切り替えに対応していないため ${from}bps のまま使います")
            needsResync = true
            sendRaw("", 500)
            return
        }
        if (!transport.setBaudRate(target)) {
            logger.log(CommLogger.Direction.ERROR, "端末側の通信速度を ${target}bps にできませんでした")
            Thread.sleep(700) // アダプターが元の速度に戻るのを待つ
            transport.purge()
            sendRaw("", 500)
            return
        }
        currentBaud = target
        Thread.sleep(20)
        transport.purge()
        // 新しい速度で CR を送り、プロンプトが返れば成功
        val ok = sendRaw("", 600).let { !lastTimedOut } && command("ATI", 600).any { it.any(Char::isLetter) } && !lastTimedOut
        if (ok) {
            logger.log(CommLogger.Direction.INFO, "通信速度を ${target}bps に切り替えました")
        } else {
            logger.log(CommLogger.Direction.ERROR, "${target}bps では通信できなかったため ${from}bps に戻します")
            transport.setBaudRate(from)
            currentBaud = from
            Thread.sleep(700)
            transport.purge()
            sendRaw("", 500)
        }
    }

    override fun connectVehicle(): String? {
        // 前回のカスタム PID のヘッダーが残っていれば既定に戻す
        if (currentHeader.isNotEmpty() || currentCea.isNotEmpty()) applyAddressing("", "")
        // 車両との接続確立 (自動判別ならここでプロトコルが決まる)
        val first = command("0100", options.searchTimeoutMs)
        val ok = parseMode01(first, 0x00) != null
        // イグニッション OFF でも ECU は起きていて否定応答 (7F 01 22) を返す。これも「接続できた」とみなす
        val refused = negativeResponse(first, 0x01)
        if (!ok && refused == null) {
            lastFailure = checkError(first) ?: first.joinToString("|").ifEmpty { "応答なし" }
            lastResponded = false
            return null
        }
        lastFailure = refused
        lastResponded = refused != null
        val dp = command("ATDPN").lastOrNull()?.trim() ?: "?"
        protocolNumber = dp.removePrefix("A")
        val desc = command("ATDP").lastOrNull()?.trim() ?: "?"
        return "$desc (ATDPN=$dp)" + if (ok) "" else " / ECU 応答あり (イグニッション OFF)"
    }

    /** ATDPN の結果 ("6" など。自動判別時の先頭 "A" は除く) */
    private var protocolNumber = options.protocol

    /** いま設定しているヘッダー / 拡張アドレス ("" = 既定) */
    private var currentHeader = ""
    private var currentCea = ""
    /** フロー制御を設定したときのヘッダー (BMW 方式) */
    private var currentFcHeader = ""

    /** プロトコルごとの既定ヘッダー (機能アドレス)。ATSH を戻すときに使う。 */
    private fun defaultHeader(): String = when (protocolNumber) {
        "6", "8" -> "7DF"
        "7", "9" -> "18DB33F1"
        "3" -> "686AF1"
        "4", "5" -> "C133F1"
        else -> "7DF"
    }

    private fun applyAddressing(header: String, cea: String) {
        if (header != currentHeader) {
            command("ATSH" + header.ifEmpty { defaultHeader() })
            currentHeader = header
        }
        if (cea != currentCea || (cea.isNotEmpty() && header != currentFcHeader)) {
            if (cea.isEmpty()) {
                command("ATCEA")
                // BMW 方式の設定を解除 (受信フィルター・フロー制御・ヘッダー表示を既定に戻す)
                command("ATCRA")
                command("ATFCSM0")
                command("ATH0")
                currentFcHeader = ""
            } else {
                command("ATCEA$cea")
                // BMW 方式 (6F1 + 拡張アドレス): ECU (0x6xx) からの応答だけを受け、
                // 複数フレームの応答を受け取れるよう、拡張アドレス付きのフロー制御を送る
                val ecu = cea.toIntOrNull(16)
                if (ecu != null) command("ATCRA%03X".format(0x600 + ecu))
                val fcHeader = header.ifEmpty { defaultHeader() }
                command("ATFCSH$fcHeader")
                command("ATFCSD${cea}300000")
                command("ATFCSM1")
                // 拡張アドレス付きの応答はヘッダー表示 ON で受け取り、自前でフレームをつなげる
                command("ATH1")
                currentFcHeader = header
            }
            currentCea = cea
        }
    }

    override fun query(request: PidRequest): IntArray? {
        if (request.isPlainMode01 && currentHeader.isEmpty() && currentCea.isEmpty()) return readPid(request.pid[0])
        applyAddressing(request.header, request.canExtAddress)
        // 登録は番号 (21 F0 / 21 F1 …) ごとに別に覚えておく
        val addr = request.header + "/" + request.canExtAddress + "/" + Hex.of(request.pid)
        val key = setupKey(request)
        if (request.setup.isNotEmpty() && setupDone[addr] != key) {
            // ECU が覚えられる登録は 1 つなので、違う登録を送ったら前の登録は無効になる
            setupDone.remove(addr)
            if (!runSetup(request.setup)) return null
            setupDone[addr] = key
        }
        val cmd = buildString {
            append("%02X".format(request.mode))
            request.pid.forEach { append("%02X".format(it)) }
        }
        val lines = command(cmd, options.commandTimeoutMs)
        if (currentCea.isNotEmpty()) {
            // 拡張アドレス付き (ヘッダー表示 ON): フレームを自前でつなげる
            val msg = ValueSearch.assemble(lines)
            val echo = intArrayOf(request.mode + 0x40) + request.pid
            if (msg != null && msg.size >= echo.size && echo.indices.all { msg[it] == echo[it] }) {
                return ok(msg.copyOfRange(echo.size, msg.size))
            }
            if (request.setup.isNotEmpty()) setupDone.remove(addr)
            if (msg != null && msg.isNotEmpty() && msg[0] == 0x7F) {
                lastResponded = true
                lastFailure = "否定応答 ${Hex.of(msg)} (NRC=%02X)".format(msg.getOrElse(2) { 0 })
                return null
            }
            return fail(lines, request.mode)
        }
        val data = parseResponseAll(lines, request.mode, request.pid).firstOrNull()
        if (data != null) return ok(data)
        if (request.setup.isNotEmpty()) setupDone.remove(addr)
        return fail(lines, request.mode)
    }

    /** 宛先ごとに、いま ECU に送ってある準備の要求 (アダプターの初期化で消える) */
    private val setupDone = mutableMapOf<String, String>()

    private fun setupKey(r: PidRequest): String =
        r.header + "/" + r.canExtAddress + "/" + r.setup.joinToString(";") { Hex.of(it) }

    /** 準備の要求を順に送る。最後の要求が肯定応答なら true (途中の消去などは断られても続ける) */
    private fun runSetup(setup: List<IntArray>): Boolean {
        for ((i, p) in setup.withIndex()) {
            val resp = sendPayload(p)
            val positive = resp != null && resp.isNotEmpty() && resp[0] == p[0] + 0x40
            if (!positive && i == setup.lastIndex) {
                lastResponded = resp != null
                lastFailure = "準備の要求 ${Hex.of(p)} が通りませんでした: ${resp?.let { Hex.of(it) } ?: "応答なし"}"
                logger.log(CommLogger.Direction.ERROR, lastFailure!!)
                return false
            }
        }
        return true
    }

    private fun hex(b: List<Int>) = b.joinToString("") { "%02X".format(it and 0xFF) }

    /** サービスデータを送り、応答のサービスデータを返す。1 フレームに収まらなければ手で分割して送る */
    private fun sendPayload(p: IntArray): IntArray? {
        val ea = currentCea.toIntOrNull(16)
        val maxSingle = if (ea != null) 6 else 7
        if (p.size <= maxSingle) {
            val lines = command(hex(p.toList()), options.commandTimeoutMs)
            return if (ea != null) ValueSearch.assemble(lines)
            else lines.map { it.replace(" ", "") }.firstOrNull { l -> l.isNotEmpty() && l.all { it.isDigit() || it in 'A'..'F' } }
                ?.let { Hex.parse(it) }
        }
        return sendSegmented(p, ea)
    }

    /**
     * ELM327 は 1 フレームに収まらない要求を送れないので、自動整形 (CAF) を止めて
     * ISO-TP の先頭フレーム・続きのフレームを手で組み立てて送る。応答 (1 フレーム) を返す
     */
    private fun sendSegmented(p: IntArray, ea: Int?): IntArray? {
        val pre = if (ea != null) listOf(ea) else emptyList()
        command("ATCAF0")
        if (ea != null) command("ATCEA") // 拡張アドレスはこちらでフレームに入れる
        else command("ATH1")
        try {
            val ffData = 8 - pre.size - 2
            val cfData = 8 - pre.size - 1
            var lines = command(hex(pre + listOf(0x10 or ((p.size shr 8) and 0x0F), p.size and 0xFF) + p.take(ffData)))
            var idx = ffData
            var sn = 1
            while (idx < p.size) {
                val part = p.slice(idx until minOf(p.size, idx + cfData))
                val frame = pre + listOf(0x20 or (sn and 0x0F)) + part
                lines = command(hex(frame + List(8 - frame.size) { 0 }))
                idx += cfData
                sn++
            }
            return if (ea != null) ValueSearch.assemble(lines)
            else lines.map { it.replace(" ", "") }.firstOrNull { it.length >= 5 }?.let { l ->
                val b = Hex.parse(l.substring(3))
                if (b.isEmpty()) null else b.copyOfRange(1, minOf(b.size, 1 + (b[0] and 0x0F)))
            }
        } finally {
            command("ATCAF1")
            if (ea != null) command("ATCEA$currentCea") else command("ATH0")
        }
    }

    override var lastFailure: String? = null
        private set

    override fun readPid(pid: Int): IntArray? {
        // 直前にカスタム PID でヘッダーを変えていたら、既定 (機能アドレス) に戻す
        if (currentHeader.isNotEmpty() || currentCea.isNotEmpty()) applyAddressing("", "")
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

    override var lastResponded: Boolean = false
        private set

    private fun ok(data: IntArray): IntArray { lastFailure = null; lastResponded = false; return data }

    private fun fail(lines: List<String>, mode: Int = 0x01): IntArray? {
        val refused = negativeResponse(lines, mode)
        lastResponded = refused != null
        lastFailure = refused ?: checkError(lines) ?: lines.joinToString("|").ifEmpty { "応答なし" }
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

    /** 直前のコマンドがタイムアウトしたか (アダプター無応答の判定用) */
    private var lastTimedOut = false

    private fun sendRaw(cmd: String, timeoutMs: Int): String {
        lastTimedOut = false
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
                lastTimedOut = true
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

        private val FRAME_INDEX = Regex("^([0-9A-F]):")

        /** "7F 22 31" のような否定応答を説明付きの文字列にする。 */
        internal fun negativeResponse(lines: List<String>, mode: Int): String? {
            for (line in lines) {
                val c = line.replace(" ", "").uppercase()
                val idx = c.indexOf("7F%02X".format(mode))
                if (idx in listOf(0, 3, 5, 8, 10) && c.length >= idx + 6) {
                    val nrc = c.substring(idx + 4, idx + 6).toInt(16)
                    val reason = when (nrc) {
                        0x11 -> "サービス非対応"
                        0x12 -> "サブ機能非対応"
                        0x13 -> "長さ・形式が不正"
                        0x22 -> "条件が整っていない"
                        0x31 -> "この PID / DID は範囲外 (非対応)"
                        0x33 -> "セキュリティ拒否"
                        0x7F -> "このセッションでは非対応"
                        else -> "NRC %02X".format(nrc)
                    }
                    return "否定応答 7F %02X %02X: %s".format(mode, nrc, reason)
                }
            }
            return null
        }

        /**
         * 任意モードの応答を解析し、[mode]+0x40 と [pid] のエコーの後ろのデータ部を返す (ECU ごとに 1 件)。
         * ELM327 が ISO-TP の複数フレームを組み立てて出す形式
         *   "00A" / "0:62123401..." / "1:0203..."
         * にも対応する。
         */
        internal fun parseResponseAll(lines: List<String>, mode: Int, pid: IntArray): List<IntArray> {
            val prefix = buildString {
                append("%02X".format(mode + 0x40))
                pid.forEach { append("%02X".format(it)) }
            }
            val compact = lines.map { it.replace(" ", "").uppercase() }
            val messages = mutableListOf<String>()
            if (compact.any { FRAME_INDEX.containsMatchIn(it) }) {
                // 複数フレーム: 先頭の長さ行 ("00A") を読み、"n:" 行をつなげる
                var total = -1
                val sb = StringBuilder()
                for (line in compact) {
                    val m = FRAME_INDEX.find(line)
                    if (m != null) {
                        sb.append(line.substring(m.range.last + 1))
                    } else if (line.length == 3 && line.all { it.isDigit() || it in 'A'..'F' }) {
                        if (sb.isNotEmpty()) { messages += trimTo(sb.toString(), total); sb.clear() }
                        total = line.toInt(16)
                    }
                }
                if (sb.isNotEmpty()) messages += trimTo(sb.toString(), total)
            } else {
                messages += compact.filter { l -> l.isNotEmpty() && l.all { it.isDigit() || it in 'A'..'F' } }
            }
            val result = mutableListOf<IntArray>()
            for (msg in messages) {
                val idx = listOf(0, 3, 5, 8, 10).firstOrNull { msg.startsWith(prefix, it) } ?: continue
                val bytes = Hex.parse(msg.substring(idx))
                val skip = 1 + pid.size
                if (bytes.size < skip) continue
                result += bytes.copyOfRange(skip, bytes.size)
            }
            return result
        }

        /** 複数フレームの末尾にある埋め草を、長さ行で示されたバイト数に切り詰める。 */
        private fun trimTo(hex: String, bytes: Int): String =
            if (bytes > 0 && hex.length > bytes * 2) hex.substring(0, bytes * 2) else hex

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
