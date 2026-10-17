package com.obdmeter.core

/**
 * OBD2 の Mode 01 (現在値) を読むためのプロトコル共通インターフェース。
 * 実装: [Elm327Protocol] (OBDLink SX/EX 等), [BmwFastProtocol] (K+DCAN ケーブル), [SimulatorProtocol]。
 * すべてブロッキング呼び出し。
 */
interface ObdProtocol {
    /** アダプターと車両の初期化を行い、接続先の説明文を返す。失敗時は例外。 */
    fun initialize(): String

    /**
     * Mode 01 の [pid] を問い合わせ、応答のデータ部 (A, B, C...) を返す。
     * 車両が対応していない / 応答なしの場合は null。
     */
    fun readPid(pid: Int): IntArray?

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
            for (i in 0 until 32) {
                val byte = data[i / 8]
                val bit = 7 - (i % 8)
                if ((byte shr bit) and 1 == 1) result += base + i + 1
            }
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
