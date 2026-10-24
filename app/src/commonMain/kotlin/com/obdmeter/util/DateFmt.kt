package com.obdmeter.util

/**
 * 日時の書式 (端末のタイムゾーン)。SimpleDateFormat の代わり。
 * 使える記号: yyyy MM M dd d HH H mm ss SSS (それ以外の文字はそのまま。'…' で囲んだ部分もそのまま)
 */
class DateFmt(private val pattern: String) {
    fun format(ms: Long): String {
        val p = localParts(ms)
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
                sb.append(pattern, i + 1, end)
                i = end + 1
                continue
            }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            when (c) {
                'y' -> sb.append(if (n == 2) (p[0] % 100).toString().padStart(2, '0') else p[0].toString().padStart(n, '0'))
                'M' -> sb.append(p[1].toString().padStart(n, '0'))
                'd' -> sb.append(p[2].toString().padStart(n, '0'))
                'H' -> sb.append(p[3].toString().padStart(n, '0'))
                'm' -> sb.append(p[4].toString().padStart(n, '0'))
                's' -> sb.append(p[5].toString().padStart(n, '0'))
                'S' -> sb.append(p[6].toString().padStart(3, '0').take(n))
                else -> repeat(n) { sb.append(c) }
            }
            i += n
        }
        return sb.toString()
    }

    /** [text] を読む。書式に合わなければ null (数字の桁数は厳密に見る) */
    fun parse(text: String): Long? {
        var y = 1970; var mo = 1; var d = 1; var h = 0; var mi = 0; var s = 0
        var i = 0
        var t = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
                val lit = pattern.substring(i + 1, end)
                if (!text.startsWith(lit, t)) return null
                t += lit.length
                i = end + 1
                continue
            }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            if (c in "yMdHms") {
                // 数字を読む (1 文字の記号は 1〜2 桁、それ以外は記号の数だけ)
                val maxLen = if (n == 1) 2 else n
                var len = 0
                while (t + len < text.length && len < maxLen && text[t + len].isDigit()) len++
                if (len == 0 || (n > 1 && len != n)) return null
                val v = text.substring(t, t + len).toInt()
                t += len
                when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v; 'H' -> h = v; 'm' -> mi = v; 's' -> s = v }
            } else {
                for (k in 0 until n) {
                    if (t >= text.length || text[t] != c) return null
                    t++
                }
            }
            i += n
        }
        if (t != text.length) return null
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..60) return null
        val ms = localToMs(y, mo, d, h, mi, s)
        // 存在しない日付 (2/30 など) を弾く
        val p = localParts(ms)
        if (p[0] != y || p[1] != mo || p[2] != d) return null
        return ms
    }
}
