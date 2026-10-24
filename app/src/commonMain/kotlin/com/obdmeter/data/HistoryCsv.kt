package com.obdmeter.data

import com.obdmeter.util.DateFmt

/** 記録した値 1 つ (時刻はミリ秒) */
class Sample(val t: Long, val v: Float)

/**
 * 折れ線グラフの記録の CSV (key,datetime,value) の読み書きと、記録の区間の計算 (データベースは使わない)
 */
object HistoryCsv {
    const val HEADER = "key,datetime,value"

    /** CSV の時刻の書式 (端末のタイムゾーン) */
    fun dateFormat() = DateFmt("yyyy-MM-dd HH:mm:ss")

    /** 読み込みで受け付ける時刻の書式 (書き出しの形、スラッシュ区切り、ISO 風) */
    fun importFormats(): List<DateFmt> = listOf(
        dateFormat(),
        DateFmt("yyyy/MM/dd HH:mm:ss"),
        DateFmt("yyyy-MM-dd'T'HH:mm:ss"),
    )

    /** 見出し行か */
    fun isHeader(line: String): Boolean = line.lowercase().startsWith("key")

    /** CSV 1 行を (キー, 時刻ミリ秒, 値) にする。読めなければ null */
    fun parseLine(line: String, formats: List<DateFmt> = importFormats()): Triple<String, Long, Double>? {
        val cols = line.split(',').map { it.trim().trim('"') }
        if (cols.size < 3) return null
        val key = cols[0].takeIf { it.isNotEmpty() } ?: return null
        val t = formats.firstNotNullOfOrNull { f -> f.parse(cols[1]) } ?: return null
        val v = cols[2].toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() } ?: return null
        return Triple(key, t, v)
    }

    /** 書き出す 1 行 (改行なし) */
    fun line(key: String, time: Long, value: Double, fmt: DateFmt = dateFormat()): String =
        key + "," + fmt.format(time) + "," + formatValue(value)

    /** 値の書き方 (整数はそのまま、それ以外は小数) */
    fun formatValue(v: Double): String =
        if (v == kotlin.math.round(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    /**
     * 記録のある短い区間 (古い順の 開始・終了) を、間が [gapMs] 以下ならつないで、記録のある時間帯にまとめる
     */
    fun mergeSegments(buckets: List<Pair<Long, Long>>, gapMs: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var a = -1L
        var b = -1L
        for ((lo, hi) in buckets) {
            if (a < 0) { a = lo; b = hi }
            else if (lo - b <= gapMs) b = maxOf(b, hi)
            else { out += a to b; a = lo; b = hi }
        }
        if (a >= 0) out += a to b
        return out
    }
}
