package com.obdmeter.core

import com.obdmeter.util.format

/**
 * ECU の応答の中から、既知の値 (BimmerLink に出ている失火カウンタなど) を探す。
 * 項目の並び順 (気筒順か点火順か) は分からないので、順番は問わずに探す。
 */
object ValueSearch {

    /**
     * ヘッダー表示 ON・拡張アドレス付き (BMW 方式) の ELM の応答行をつなげて、1 つのメッセージにする。
     * 例: "612F1102F624000240C" (先頭フレーム) + "612F121000000000000" (続き) …
     * @return メッセージのバイト列 (サービス ID から)。読めなければ null
     */
    fun assemble(lines: List<String>): IntArray? {
        val out = ArrayList<Int>()
        var total = -1
        for (raw in lines) {
            val hex = raw.replace(" ", "").uppercase()
            if (hex.length < 3 + 4 || !hex.all { it.isDigit() || it in 'A'..'F' }) continue
            val b = Hex.parse(hex.substring(3))  // 先頭 3 文字は CAN ID (612 など)
            if (b.size < 2) continue
            val f = b.copyOfRange(1, b.size)     // 拡張アドレス (F1) を除く
            val pci = f[0]
            when (pci shr 4) {
                0 -> { total = pci and 0x0F; out.clear(); for (i in 1 until f.size) out += f[i] }
                1 -> { total = ((pci and 0x0F) shl 8) or f.getOrElse(1) { 0 }; out.clear(); for (i in 2 until f.size) out += f[i] }
                2 -> for (i in 1 until f.size) out += f[i]
                else -> {}
            }
        }
        if (out.isEmpty()) return null
        return (if (total in 1..out.size) out.subList(0, total) else out).toIntArray()
    }

    /**
     * [data] の中に [targets] が並んでいるところを探す。1・2・4 バイトの整数 (ビッグ / リトルエンディアン)、
     * 10 倍・100 倍の整数、4 バイトの小数 (float) で読み、連続した [targets] 個の値のうち
     * いくつが targets と一致するか (順番は問わない) を数える。
     * @return 一番よく一致した読み方の説明。一致が [minMatch] 未満なら null
     */
    fun match(data: IntArray, targets: List<Long>, minMatch: Int = minOf(targets.size, 4)): String? {
        if (targets.isEmpty()) return null
        var best: Pair<Int, String>? = null
        fun consider(values: List<Double>, offset: Int, width: Int, label: String, want: List<Double>) {
            for (start in 0..(values.size - want.size).coerceAtLeast(0)) {
                val window = values.subList(start, minOf(values.size, start + want.size)).toMutableList()
                var n = 0
                for (t in want) {
                    val idx = window.indexOfFirst { kotlin.math.abs(it - t) < 0.005 }
                    if (idx >= 0) { n++; window.removeAt(idx) }
                }
                if (n >= minMatch && (best == null || n > best!!.first)) {
                    val pos = offset + start * width
                    val shown = values.subList(start, minOf(values.size, start + want.size))
                        .joinToString(",") { v -> if (v == kotlin.math.round(v)) v.toLong().toString() else "%.2f".format(v) }
                    best = n to "$label 位置 $pos から: $shown (一致 $n/${want.size})"
                }
            }
        }
        for (width in listOf(1, 2, 4)) {
            for (little in if (width == 1) listOf(false) else listOf(false, true)) {
                for (offset in 0 until width) {
                    val raw = ArrayList<Long>()
                    var i = offset
                    while (i + width <= data.size) {
                        var v = 0L
                        for (k in 0 until width) v = (v shl 8) or data[i + (if (little) width - 1 - k else k)].toLong()
                        raw += v
                        i += width
                    }
                    val endian = if (width > 1) (if (little) "(リトル)" else "(ビッグ)") else ""
                    for (scale in listOf(1, 10, 100)) {
                        val want = targets.map { (it * scale).toDouble() }
                        if (width == 1 && want.any { it > 255 }) continue
                        val label = "${width}バイト$endian" + if (scale > 1) " ×$scale" else ""
                        consider(raw.map { it.toDouble() }, offset, width, label, want)
                    }
                    if (width == 4) {
                        val floats = raw.map { Float.fromBits(it.toInt()).toDouble() }
                            .map { if (it.isNaN() || it.isInfinite()) -1e30 else it }
                        consider(floats, offset, width, "小数(float)$endian", targets.map { it.toDouble() })
                    }
                }
            }
        }
        return best?.second
    }
}
