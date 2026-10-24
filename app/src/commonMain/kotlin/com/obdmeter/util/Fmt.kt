package com.obdmeter.util

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Java の String.format の代わり (Android と Web で同じ結果になるように)。
 * 対応する書き方: %d %s %c %f %x %X %% と、フラグ (- 0 + 空白 ,)・幅・精度 (例: %.1f %02X %5d %-8s)
 */
fun String.format(vararg args: Any?): String {
    val s = this
    val out = StringBuilder()
    var i = 0
    var argIndex = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '%') { out.append(c); i++; continue }
        i++
        if (i >= s.length) { out.append('%'); break }
        if (s[i] == '%') { out.append('%'); i++; continue }
        var left = false; var zero = false; var plus = false; var space = false; var group = false
        while (i < s.length && s[i] in "-0+ ,") {
            when (s[i]) { '-' -> left = true; '0' -> zero = true; '+' -> plus = true; ' ' -> space = true; ',' -> group = true }
            i++
        }
        var width = 0
        while (i < s.length && s[i].isDigit()) { width = width * 10 + (s[i] - '0'); i++ }
        var precision = -1
        if (i < s.length && s[i] == '.') {
            i++
            precision = 0
            while (i < s.length && s[i].isDigit()) { precision = precision * 10 + (s[i] - '0'); i++ }
        }
        if (i >= s.length) break
        val conv = s[i]
        i++
        val arg = args.getOrNull(argIndex++)
        var numeric = true
        var body: String = when (conv) {
            'd' -> {
                val v = (arg as? Number)?.toLong() ?: 0L
                var t = abs(v).toString()
                if (v == Long.MIN_VALUE) t = "9223372036854775808"
                if (group) t = groupDigits(t)
                sign(v < 0, plus, space) + t
            }
            'f' -> {
                val v = (arg as? Number)?.toDouble() ?: 0.0
                fixed(v, if (precision < 0) 6 else precision, plus, space, group)
            }
            'x', 'X' -> {
                val t = when (arg) {
                    is Int -> arg.toUInt().toString(16)
                    is Long -> arg.toULong().toString(16)
                    is Short -> arg.toUShort().toString(16)
                    is Byte -> arg.toUByte().toString(16)
                    is Number -> arg.toLong().toULong().toString(16)
                    else -> "0"
                }
                if (conv == 'X') t.uppercase() else t
            }
            'c' -> { numeric = false; (arg as? Char)?.toString() ?: arg.toString() }
            's', 'S' -> {
                numeric = false
                val t = arg.toString()
                val p = if (precision >= 0 && precision < t.length) t.substring(0, precision) else t
                if (conv == 'S') p.uppercase() else p
            }
            else -> { numeric = false; "%$conv" }
        }
        if (body.length < width) {
            val pad = width - body.length
            body = when {
                left -> body + " ".repeat(pad)
                zero && numeric -> {
                    val signLen = if (body.startsWith("-") || body.startsWith("+") || body.startsWith(" ")) 1 else 0
                    body.substring(0, signLen) + "0".repeat(pad) + body.substring(signLen)
                }
                else -> " ".repeat(pad) + body
            }
        }
        out.append(body)
    }
    return out.toString()
}

private fun sign(negative: Boolean, plus: Boolean, space: Boolean): String =
    if (negative) "-" else if (plus) "+" else if (space) " " else ""

private fun groupDigits(t: String): String {
    val sb = StringBuilder()
    for ((k, ch) in t.withIndex()) {
        if (k > 0 && (t.length - k) % 3 == 0) sb.append(',')
        sb.append(ch)
    }
    return sb.toString()
}

/** 小数 [n] 桁の固定小数点 (四捨五入は Java と同じく 0.5 を切り上げ) */
private fun fixed(v: Double, n: Int, plus: Boolean, space: Boolean, group: Boolean): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) (if (plus) "+Infinity" else "Infinity") else "-Infinity"
    val negative = v < 0 || (v == 0.0 && 1.0 / v < 0)
    val a = abs(v)
    val scale = 10.0.pow(n)
    val scaled = a * scale
    val digits: String
    if (scaled < 9.0e15) {
        val r = floor(scaled + 0.5).toLong()
        val p = 10.0.pow(n).toLong()
        val ip = r / p
        val fp = r % p
        val ipText = if (group) groupDigits(ip.toString()) else ip.toString()
        digits = if (n == 0) ipText else ipText + "." + fp.toString().padStart(n, '0')
    } else {
        // とても大きい値: 整数部はそのまま、小数部は 0
        val t = integerText(floor(a))
        digits = if (n == 0) t else t + "." + "0".repeat(n)
    }
    // 丸めた結果が 0 でも、元が負なら Java と同じく "-0.0"
    return sign(negative, plus, space) + digits
}

/** 整数値の小数を 10 進の整数の文字列にする (指数表記にしない) */
private fun integerText(v: Double): String {
    if (v < 9.0e18) return v.toLong().toString()
    val str = v.toString()  // 例: 1.0E20
    val e = str.indexOfFirst { it == 'E' || it == 'e' }
    if (e < 0) return str.substringBefore('.')
    val mant = str.substring(0, e)
    val exp = str.substring(e + 1).toInt()
    val intPart = mant.substringBefore('.')
    val frac = mant.substringAfter('.', "").trimEnd('0')
    val digits = intPart + frac
    val zeros = exp - frac.length
    return if (zeros >= 0) digits + "0".repeat(zeros) else digits.dropLast(-zeros)
}
