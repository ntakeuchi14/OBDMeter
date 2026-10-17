package com.obdmeter.core

/**
 * OBD2 の Mode 01 (現在値) を読むためのプロトコル共通インターフェース。
 * 実装: [Elm327Protocol] (OBDLink SX/EX 等), [BmwFastProtocol] (K+DCAN ケーブル), [SimulatorProtocol]。
 * すべてブロッキング呼び出し。
 */
interface ObdProtocol {
    /**
     * アダプター自体の初期化。車両が眠っていても (イグニッション OFF) 成功する。
     * アダプターが応答しない場合は例外。説明文を返す。
     */
    fun initAdapter(): String

    /**
     * 車両 (エンジン ECU) と通信できるか確認する。
     * 応答があれば説明文、無ければ null ([lastFailure] に理由)。アダプター自体の異常なら例外。
     */
    fun connectVehicle(): String?

    /** アダプターと車両の両方を初期化する。車両が応答しなければ例外。 */
    fun initialize(): String {
        val adapter = initAdapter()
        val vehicle = connectVehicle()
            ?: throw ProtocolException("車両に接続できません: ${lastFailure ?: "応答なし"}")
        return "$adapter / $vehicle"
    }

    /**
     * Mode 01 の [pid] を問い合わせ、応答のデータ部 (A, B, C...) を返す。
     * 車両が対応していない / 応答なしの場合は null。
     */
    fun readPid(pid: Int): IntArray?

    /** 直近の readPid / query が失敗した理由 (ログ表示用)。 */
    val lastFailure: String? get() = null

    /**
     * 直近の失敗が「ECU は応答したが拒否した (否定応答 7F xx yy)」だったか。
     * イグニッション OFF でも ECU は起きていて 7F 01 22 (条件不成立) を返すので、
     * 「車両とはつながっている」と判断するのに使う。
     */
    val lastResponded: Boolean get() = false

    /**
     * 任意のモード・PID を問い合わせる (カスタム PID 用)。
     * 応答からサービス ID (0x40+mode) と PID のエコーを除いたデータ部を返す。
     * 既定実装は Mode 01・ヘッダー指定なしのときだけ [readPid] に委ねる。
     */
    fun query(request: PidRequest): IntArray? =
        if (request.isPlainMode01) readPid(request.pid[0]) else null

    /** アダプター自身が測るバッテリー電圧 (ELM327 の ATRV)。非対応なら null。 */
    fun readAdapterVoltage(): Double? = null

    fun close()

    /** Mode 01 PID 00/20/40/60... のビットマップから対応 PID 一覧を得る。 */
    fun readSupportedPids(): Set<Int> {
        val result = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xA0) {
            val data = readPid(base) ?: break
            if (data.size < 4) break
            result += decodeBitmap(base, data)
            // 次のブロック (base+0x20) に対応していなければ終了
            if (!result.contains(base + 0x20)) break
            base += 0x20
        }
        return result
    }
}

class ProtocolException(message: String) : Exception(message)

/** 読み出しタイムアウト付きで、ちょうど n バイト受信する補助関数。 */
internal fun Transport.readExact(n: Int, timeoutMs: Int): ByteArray? {
    val out = ByteArray(n)
    var got = 0
    val deadline = System.currentTimeMillis() + timeoutMs
    while (got < n) {
        val remaining = (deadline - System.currentTimeMillis()).toInt()
        if (remaining <= 0) return null
        // 残りバイト数ぴったりのバッファで読むので、次のテレグラムを食い込まない
        val tmp = ByteArray(n - got)
        val r = read(tmp, minOf(remaining, 100))
        if (r <= 0) continue
        System.arraycopy(tmp, 0, out, got, r)
        got += r
    }
    return out
}

/** PID 00/20/40... の 4 バイトのビットマップを PID 番号の集合に変換する。 */
fun decodeBitmap(base: Int, data: IntArray): Set<Int> {
    val out = mutableSetOf<Int>()
    for (i in 0 until 32) {
        if ((data[i / 8] shr (7 - i % 8)) and 1 == 1) out += base + i + 1
    }
    return out
}

/**
 * 1 回の問い合わせ内容。
 * @param mode サービス (01 = 現在値、22 = メーカー独自 DID 読み出し など)
 * @param pid  PID / DID のバイト列 (Mode 01 なら 1 バイト、Mode 22 なら通常 2 バイト)
 * @param header 送信ヘッダー。ELM327 なら ATSH の値 (例 "7E0")、K+DCAN なら送信先 ECU アドレス (例 "12")。空なら既定
 * @param canExtAddress CAN 拡張アドレス (ELM327 の ATCEA)。BMW の 6F1 系で使う。空なら使わない
 */
class PidRequest(
    val mode: Int,
    val pid: IntArray,
    val header: String = "",
    val canExtAddress: String = "",
) {
    val isPlainMode01: Boolean get() = mode == 0x01 && pid.size == 1 && header.isEmpty() && canExtAddress.isEmpty()

    /** "01 0C" / "22 1234 @7E0" のようなログ用表記 */
    override fun toString(): String = buildString {
        append("%02X ".format(mode))
        pid.forEach { append("%02X".format(it)) }
        if (header.isNotEmpty()) append(" @").append(header)
        if (canExtAddress.isNotEmpty()) append(" CEA=").append(canExtAddress)
    }

    companion object {
        /** "22", "1234" のような 16 進文字列から作る。不正なら例外。 */
        fun parse(modeHex: String, pidHex: String, header: String = "", cea: String = ""): PidRequest {
            val mode = modeHex.trim().toIntOrNull(16)?.takeIf { it in 0x01..0x3F }
                ?: throw IllegalArgumentException("モードは 01〜3F の 16 進数で入力してください")
            val p = pidHex.replace(" ", "")
            if (p.length % 2 != 0 || p.length > 8 || p.any { !(it.isDigit() || it.uppercaseChar() in 'A'..'F') }) {
                throw IllegalArgumentException("PID は 16 進数 (2〜8 桁、偶数桁) で入力してください")
            }
            val h = header.trim().uppercase()
            if (h.isNotEmpty() && (h.length !in 2..8 || h.any { !(it.isDigit() || it in 'A'..'F') })) {
                throw IllegalArgumentException("ヘッダーは 16 進数で入力してください (例: 7E0)")
            }
            val c = cea.trim().uppercase()
            if (c.isNotEmpty() && (c.length != 2 || c.any { !(it.isDigit() || it in 'A'..'F') })) {
                throw IllegalArgumentException("拡張アドレスは 16 進 2 桁で入力してください (例: 12)")
            }
            return PidRequest(mode, Hex.parse(p), h, c)
        }
    }
}
