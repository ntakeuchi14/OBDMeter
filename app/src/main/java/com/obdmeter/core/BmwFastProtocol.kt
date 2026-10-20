package com.obdmeter.core

/**
 * INPA 互換 K+DCAN ケーブル (FTDI) 用の BMW-FAST テレグラム実装。
 *
 * テレグラム形式 (EdiabasLib の TransBmwFast と同じ):
 *   [0x80 | len] [target] [source] [data...] [checksum]        (len <= 0x3F)
 *   [0x80]       [target] [source] [len] [data...] [checksum]  (len >  0x3F)
 *   checksum = 先頭からデータ末尾までの単純加算 (下位 8bit)
 *
 * ケーブルは 115200bps 固定で、D-CAN モードではケーブルが BMW 独自の CAN (ID 0x6F1 + 拡張アドレス) に変換する。
 * K-Line モード (2007/03 以前の E90, ケーブルのスイッチで切替) でも同じテレグラムがそのまま流れる。
 * ケーブルによって、送信内容をエコーするもの・しないもの、応答の前に意味のないバイト (00 00 など) を
 * 付けるものがある。受信は「チェックサムが合い、ECU → テスター (F1) 宛てのテレグラム」を探して読むので、
 * エコーや余計なバイトは自動で読み飛ばす。
 *
 * 注意: 汎用 OBD (CAN ID 0x7DF/0x7E0) は使えないため、DME (0x12) に直接 Mode 01 を送る。
 * DME がこれに応答するかは実車で確認が必要 (通信ログ画面で確認できる)。
 */
class BmwFastProtocol(
    private val transport: Transport,
    private val options: Options = Options(),
    private val logger: CommLogger = CommLogger.NONE,
) : ObdProtocol {

    data class Options(
        /** 送信先 ECU アドレス。0x12 = DME/DDE (エンジン)。 */
        val targetAddress: Int = 0x12,
        /** テスター (自分) のアドレス。BMW では 0xF1 固定。 */
        val sourceAddress: Int = 0xF1,
        /** (旧設定・未使用) エコーの有無は自動で判別する */
        val adapterEcho: Boolean = true,
        val responseTimeoutMs: Int = 1000,
        /** 0x7F xx 0x78 (処理中) を受けたときに待つ最大時間。 */
        val pendingTimeoutMs: Int = 5000,
    )

    override fun initAdapter(): String =
        "K+DCAN (BMW-FAST, ECU 0x%02X)".format(options.targetAddress)

    override fun connectVehicle(): String? {
        transport.purge()
        // DME に Mode 01 PID 00 (対応 PID 一覧) を問い合わせて疎通確認
        val data = readPid(0x00)
        if (data == null && lastResponded) {
            return "ECU 0x%02X 応答あり (イグニッション OFF)".format(options.targetAddress)
        }
        if (data == null) {
            if (echoMissing) {
                // ケーブル自体が何も返さない (車両の状態とは無関係)
                throw ProtocolException("ケーブルから何も返ってきません。ケーブルの接続 (OBD ポート・USB) を確認してください")
            }
            return null
        }
        return "ECU 0x%02X (PID00=%s)".format(options.targetAddress, Hex.of(data))
    }

    /** 直前の送信で、ケーブルから 1 バイトも返らなかったか */
    var echoMissing = false
        private set

    override var lastFailure: String? = null
        private set

    override var lastResponded: Boolean = false
        private set

    override fun readPid(pid: Int): IntArray? {
        val resp = request(intArrayOf(0x01, pid)) ?: run { lastFailure = "応答なし"; lastResponded = false; return null }
        lastResponded = resp.isNotEmpty() && resp[0] == 0x7F
        if (resp.size >= 2 && resp[0] == 0x41 && resp[1] == pid) {
            lastFailure = null
            return resp.copyOfRange(2, resp.size)
        }
        lastFailure = if (resp.isNotEmpty() && resp[0] == 0x7F) {
            "否定応答 ${Hex.of(resp)} (NRC=%02X)".format(resp.getOrElse(2) { 0 })
        } else {
            "想定外の応答 ${Hex.of(resp)}"
        }
        return null
    }

    /**
     * カスタム PID。ヘッダー欄は送信先 ECU アドレス (16 進、例 "12" = DME、"18" = EGS) として扱う。
     * BMW 独自の値は Mode 22 (ReadDataByIdentifier) や Mode 21 で読めることが多い。
     */
    override fun query(request: PidRequest): IntArray? {
        val target = request.header.takeIf { it.isNotEmpty() }?.toIntOrNull(16)?.takeIf { it in 0..0xFF }
            ?: options.targetAddress
        // 読む前の準備 (測定値ブロックの登録など)。BMW-FAST は長い要求もそのまま送れる
        val key = request.setup.joinToString(";") { Hex.of(it) }
        // 登録は宛先と番号 (21 F0 / 21 F1 …) ごとに覚えておく
        val slot = "$target/" + Hex.of(request.pid)
        if (request.setup.isNotEmpty() && setupDone[slot] != key) {
            setupDone.remove(slot)
            for ((i, p) in request.setup.withIndex()) {
                val r = request(p, target)
                if (i == request.setup.lastIndex && (r == null || r.isEmpty() || r[0] != p[0] + 0x40)) {
                    lastFailure = "準備の要求 ${Hex.of(p)} が通りませんでした: ${r?.let { Hex.of(it) } ?: "応答なし"}"
                    lastResponded = r != null
                    return null
                }
            }
            setupDone[slot] = key
        }
        val resp = request(intArrayOf(request.mode) + request.pid, target)
            ?: run { lastFailure = "応答なし"; lastResponded = false; return null }
        lastResponded = resp.isNotEmpty() && resp[0] == 0x7F
        val echo = intArrayOf(request.mode + 0x40) + request.pid
        if (resp.size >= echo.size && echo.indices.all { resp[it] == echo[it] }) {
            lastFailure = null
            return resp.copyOfRange(echo.size, resp.size)
        }
        if (request.setup.isNotEmpty()) setupDone.remove(slot)
        lastFailure = if (resp.isNotEmpty() && resp[0] == 0x7F) {
            "否定応答 ${Hex.of(resp)} (NRC=%02X)".format(resp.getOrElse(2) { 0 })
        } else {
            "想定外の応答 ${Hex.of(resp)}"
        }
        return null
    }

    override fun close() {
        runCatching { transport.close() }
    }

    /** 宛先ごとに、いま ECU に送ってある準備の要求 */
    private val setupDone = mutableMapOf<String, String>()

    /**
     * サービスデータ (例: 01 0C) を送り、応答のサービスデータ部を返す。
     * 応答待ち (7F xx 78) は自動で待機する。
     */
    fun request(service: IntArray, target: Int = options.targetAddress): IntArray? {
        val tel = buildTelegram(target, options.sourceAddress, service)
        transport.purge()
        rx.clear()
        logger.log(CommLogger.Direction.TX, Hex.of(tel))
        transport.write(tel)

        echoMissing = false
        var gotAny = false
        var deadline = System.currentTimeMillis() + options.responseTimeoutMs
        val chunk = ByteArray(256)
        while (true) {
            // 受信済みのバイトから、テレグラムを順に取り出す (エコー・余計なバイトは読み飛ばす)
            while (true) {
                val found = extractTelegram(rx) ?: break
                val (t, skipped) = found
                if (skipped.isNotEmpty()) logger.log(CommLogger.Direction.INFO, "読み飛ばし: ${Hex.of(skipped.toIntArray())}")
                if (t.target != options.sourceAddress) continue // 自分が送ったテレグラムのエコー
                logger.log(CommLogger.Direction.RX, Hex.of(buildTelegram(t.target, t.source, t.data)))
                if (t.source != target) continue // 他 ECU からのテレグラム
                if (t.data.size == 3 && t.data[0] == 0x7F && t.data[2] == 0x78) {
                    // 応答待ち: もう少し待つ
                    deadline = System.currentTimeMillis() + options.pendingTimeoutMs
                    continue
                }
                return t.data
            }
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) {
                echoMissing = !gotAny
                logger.log(CommLogger.Direction.ERROR,
                    if (gotAny) "応答タイムアウト (受信: ${Hex.of(rx.toIntArray())})" else "ケーブルから受信なし")
                return null
            }
            val n = transport.read(chunk, minOf(remaining, 50))
            if (n > 0) {
                gotAny = true
                for (i in 0 until n) rx += chunk[i].toInt() and 0xFF
            }
        }
    }

    /** 受信したがまだテレグラムとして取り出していないバイト */
    private val rx = ArrayList<Int>()

    data class Telegram(val target: Int, val source: Int, val data: IntArray)

    companion object {
        fun buildTelegram(target: Int, source: Int, data: IntArray): ByteArray {
            val header = if (data.size <= 0x3F) {
                intArrayOf(0x80 or data.size, target, source)
            } else {
                require(data.size <= 0xFF) { "データが長すぎます" }
                intArrayOf(0x80, target, source, data.size)
            }
            val body = header + data
            val cs = body.sum() and 0xFF
            return ByteArray(body.size + 1) { i -> (if (i < body.size) body[i] else cs).toByte() }
        }

        /** ヘッダーからチェックサムを除いたテレグラム長を求める。 */
        fun telegramLength(head: ByteArray): Int {
            val len = head[0].toInt() and 0x3F
            return if (len == 0) (head[3].toInt() and 0xFF) + 4 else len + 3
        }

        /**
         * [buf] の先頭から、チェックサムの合うテレグラムを 1 つ探して取り出す (取り出した分と、その前の余計なバイトは
         * [buf] から消す)。見つからなければ null ([buf] はそのまま。続きの受信を待つ)。
         * 先頭バイトがヘッダーに見えても、チェックサムが合わなければ 1 バイトずらして探し直す
         */
        fun extractTelegram(buf: MutableList<Int>): Pair<Telegram, List<Int>>? {
            var i = 0
            while (i < buf.size) {
                val h = buf[i]
                if (h and 0xC0 != 0x80) { i++; continue }
                // ヘッダーに見えても、続きがまだ届いていなければ、後ろに完全なテレグラムが無いか先に探す
                if (buf.size - i < 4) { i++; continue }
                val lenField = h and 0x3F
                val len = if (lenField == 0) buf[i + 3] + 4 else lenField + 3
                if (buf.size - i < len + 1) { i++; continue }
                var sum = 0
                for (k in i until i + len) sum += buf[k]
                if ((sum and 0xFF) == buf[i + len]) {
                    val start = if (lenField == 0) 4 else 3
                    val data = IntArray(len - start) { buf[i + start + it] }
                    val t = Telegram(buf[i + 1], buf[i + 2], data)
                    val skipped = buf.subList(0, i).toList()
                    repeat(i + len + 1) { buf.removeAt(0) }
                    return t to skipped
                }
                i++
            }
            // 完全なテレグラムがまだ無い (続きの受信を待つ)
            return null
        }

        /** 受信したバイト列に含まれるテレグラムをすべて取り出す (接続テスト用) */
        fun scanTelegrams(bytes: ByteArray): List<Telegram> {
            val buf = bytes.map { it.toInt() and 0xFF }.toMutableList()
            val out = ArrayList<Telegram>()
            while (true) out += (extractTelegram(buf) ?: break).first
            return out
        }

        fun parseTelegram(frame: ByteArray): Telegram? {
            if (frame.size < 4) return null
            val len = telegramLength(frame)
            if (frame.size != len + 1) return null
            var sum = 0
            for (i in 0 until len) sum += frame[i].toInt() and 0xFF
            if ((sum and 0xFF) != (frame[len].toInt() and 0xFF)) return null
            val start = if ((frame[0].toInt() and 0x3F) == 0) 4 else 3
            val data = IntArray(len - start) { frame[start + it].toInt() and 0xFF }
            return Telegram(frame[1].toInt() and 0xFF, frame[2].toInt() and 0xFF, data)
        }
    }
}
