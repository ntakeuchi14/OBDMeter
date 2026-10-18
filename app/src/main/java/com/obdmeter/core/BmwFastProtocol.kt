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
 * どちらもケーブルが送信内容をエコーするので、受信の先頭から送信分を取り除く。
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
        /** ケーブルのエコーを取り除く。通常の K+DCAN ケーブルは true。 */
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
                // ケーブル自体が反応していない (車両の状態とは無関係)
                throw ProtocolException("ケーブルから応答 (エコー) がありません。ケーブルの接続と、設定の「エコー除去」を確認してください")
            }
            return null
        }
        return "ECU 0x%02X (PID00=%s)".format(options.targetAddress, Hex.of(data))
    }

    /** 直前の送信でケーブルのエコーが返らなかったか */
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
        if (request.setup.isNotEmpty() && setupDone[target] != key) {
            setupDone.remove(target)
            for ((i, p) in request.setup.withIndex()) {
                val r = request(p, target)
                if (i == request.setup.lastIndex && (r == null || r.isEmpty() || r[0] != p[0] + 0x40)) {
                    lastFailure = "準備の要求 ${Hex.of(p)} が通りませんでした: ${r?.let { Hex.of(it) } ?: "応答なし"}"
                    lastResponded = r != null
                    return null
                }
            }
            setupDone[target] = key
        }
        val resp = request(intArrayOf(request.mode) + request.pid, target)
            ?: run { lastFailure = "応答なし"; lastResponded = false; return null }
        lastResponded = resp.isNotEmpty() && resp[0] == 0x7F
        val echo = intArrayOf(request.mode + 0x40) + request.pid
        if (resp.size >= echo.size && echo.indices.all { resp[it] == echo[it] }) {
            lastFailure = null
            return resp.copyOfRange(echo.size, resp.size)
        }
        if (request.setup.isNotEmpty()) setupDone.remove(target)
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
    private val setupDone = mutableMapOf<Int, String>()

    /**
     * サービスデータ (例: 01 0C) を送り、応答のサービスデータ部を返す。
     * 応答待ち (7F xx 78) は自動で待機する。
     */
    fun request(service: IntArray, target: Int = options.targetAddress): IntArray? {
        val tel = buildTelegram(target, options.sourceAddress, service)
        transport.purge()
        logger.log(CommLogger.Direction.TX, Hex.of(tel))
        transport.write(tel)

        echoMissing = false
        if (options.adapterEcho) {
            val echo = transport.readExact(tel.size, 300)
            if (echo == null) {
                echoMissing = true
                logger.log(CommLogger.Direction.ERROR, "エコーが返りません (ケーブル未接続 / ボーレート違い?)")
                return null
            }
            if (!echo.contentEquals(tel)) {
                logger.log(CommLogger.Direction.ERROR, "エコー不一致: ${Hex.of(echo)}")
                transport.purge()
                return null
            }
        }

        var deadline = System.currentTimeMillis() + options.responseTimeoutMs
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) {
                logger.log(CommLogger.Direction.ERROR, "応答タイムアウト")
                return null
            }
            val frame = readTelegram(remaining) ?: run {
                logger.log(CommLogger.Direction.ERROR, "応答なし")
                return null
            }
            logger.log(CommLogger.Direction.RX, Hex.of(frame))
            val parsed = parseTelegram(frame) ?: run {
                logger.log(CommLogger.Direction.ERROR, "チェックサム / 形式エラー")
                transport.purge()
                return null
            }
            val data = parsed.data
            if (data.size == 3 && data[0] == 0x7F && data[2] == 0x78) {
                // 応答待ち: もう少し待つ
                deadline = System.currentTimeMillis() + options.pendingTimeoutMs
                continue
            }
            if (parsed.source != target) {
                // 他 ECU からのテレグラムは無視して待ち続ける
                continue
            }
            return data
        }
    }

    private fun readTelegram(timeoutMs: Int): ByteArray? {
        val head = transport.readExact(4, timeoutMs) ?: return null
        val h0 = head[0].toInt() and 0xFF
        if (h0 and 0xC0 != 0x80) {
            logger.log(CommLogger.Direction.ERROR, "不正なヘッダー: ${Hex.of(head)}")
            transport.purge()
            return null
        }
        val total = telegramLength(head) + 1 // + checksum
        val rest = transport.readExact(total - 4, 200) ?: return null
        return head + rest
    }

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
