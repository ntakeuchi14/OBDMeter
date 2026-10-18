package com.obdmeter.core

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
     * [data] の中に [targets] が並んでいるところを探す。1・2・4 バイトの整数 (ビッグ / リトルエンディアン) で読み、
     * 連続した [targets] 個の値のうち、いくつが targets と一致するか (順番は問わない) を数える。
     * @return 一番よく一致した読み方の説明。一致が [minMatch] 未満なら null
     */
    fun match(data: IntArray, targets: List<Long>, minMatch: Int = minOf(targets.size, 4)): String? {
        if (targets.isEmpty()) return null
        var best: Pair<Int, String>? = null
        for (width in listOf(1, 2, 4)) {
            if (width == 1 && targets.any { it > 255 }) continue
            for (little in if (width == 1) listOf(false) else listOf(false, true)) {
                for (offset in 0 until width) {
                    val values = ArrayList<Long>()
                    var i = offset
                    while (i + width <= data.size) {
                        var v = 0L
                        for (k in 0 until width) {
                            val byte = data[i + (if (little) width - 1 - k else k)].toLong()
                            v = (v shl 8) or byte
                        }
                        values += v
                        i += width
                    }
                    for (start in 0..(values.size - targets.size).coerceAtLeast(0)) {
                        val window = values.subList(start, minOf(values.size, start + targets.size)).toMutableList()
                        var n = 0
                        for (t in targets) {
                            val idx = window.indexOf(t)
                            if (idx >= 0) { n++; window.removeAt(idx) }
                        }
                        if (n >= minMatch && (best == null || n > best.first)) {
                            val pos = offset + start * width
                            val shown = values.subList(start, minOf(values.size, start + targets.size))
                            best = n to "${width}バイト${if (width > 1) (if (little) "(リトル)" else "(ビッグ)") else ""} " +
                                "位置 $pos から: ${shown.joinToString(",")} (一致 $n/${targets.size})"
                        }
                    }
                }
            }
        }
        return best?.second
    }
}
