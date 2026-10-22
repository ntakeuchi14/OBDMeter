package com.obdmeter.core

/**
 * アダプターとのバイト列通信を抽象化したもの。
 * USB / Wi-Fi など物理層の違いはここに閉じ込め、プロトコル層は同期 (ブロッキング) で扱う。
 * プロトコル層は必ずバックグラウンドスレッドから呼び出すこと。
 */
interface Transport {
    /** 接続を開く。失敗時は例外を投げる。 */
    fun open()

    /** データを送信する。 */
    fun write(data: ByteArray)

    /**
     * 受信したバイトを [buffer] に書き込み、そのバイト数を返す。
     * [timeoutMs] 以内に何も届かなければ 0 を返す。
     */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /** 通信速度を変える (USB シリアルのみ)。変えられなければ false */
    fun setBaudRate(baud: Int): Boolean = false

    /** 受信バッファに溜まっている不要データを捨てる。 */
    fun purge() {
        val tmp = ByteArray(256)
        var guard = 0
        while (read(tmp, 10) > 0 && guard++ < 50) { /* discard */ }
    }

    fun close()
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 通信ログの出力先。TX/RX を画面に出してデバッグするために使う。 */
fun interface CommLogger {
    fun log(direction: Direction, text: String)

    enum class Direction { TX, RX, INFO, ERROR }

    companion object {
        val NONE = CommLogger { _, _ -> }
    }
}

object Hex {
    fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
        (offset until offset + length).joinToString(" ") { "%02X".format(bytes[it].toInt() and 0xFF) }

    fun of(values: IntArray): String = values.joinToString(" ") { "%02X".format(it and 0xFF) }

    /** "41 0C 1A F8" や "410C1AF8" をバイト列に変換する。16進以外の文字は無視する。 */
    fun parse(text: String): IntArray {
        val clean = text.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
        if (clean.length % 2 != 0) return IntArray(0)
        return IntArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16) }
    }
}
