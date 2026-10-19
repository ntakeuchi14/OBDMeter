package com.obdmeter.ui.gauges

import com.obdmeter.core.PidDef
import com.obdmeter.data.Sample
import kotlin.math.abs
import kotlin.math.max

// 折れ線グラフの計算 (描画はしない)

/** グラフの項目。[right] = 右の縦軸に属する */
class ChartSeries(val def: PidDef, val value: Double?, val right: Boolean = true)

/**
 * グラフの横軸の時間。記録の無い時間 (区間の間) を [pad] の幅に詰める。
 * 「詰めた時間」(c) と実際の時刻 (t) を相互に変換する。区間が無ければそのまま
 */
internal class TimeAxis(val segs: List<Pair<Long, Long>>, val pad: Long) {
    /** 各区間の、詰めた時間での開始位置 */
    val cs = LongArray(segs.size).also { arr ->
        var c = 0L
        segs.forEachIndexed { i, (a, b) -> arr[i] = c; c += (b - a) + pad }
    }

    private fun segAt(t: Long): Int {
        var lo = 0; var hi = segs.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) / 2; if (segs[m].first <= t) { r = m; lo = m + 1 } else hi = m - 1 }
        return r
    }

    fun toC(t: Long): Long {
        if (segs.isEmpty()) return t
        val i = segAt(t)
        if (i < 0) return cs[0] - (segs[0].first - t)
        val (a, b) = segs[i]
        return if (t <= b) cs[i] + (t - a)
        else if (i == segs.lastIndex) cs[i] + (b - a) + (t - b)
        else cs[i] + (b - a) + minOf(t - b, pad)
    }

    fun toReal(c: Long): Long {
        if (segs.isEmpty()) return c
        if (c < cs[0]) return segs[0].first - (cs[0] - c)
        var lo = 0; var hi = cs.size - 1; var i = 0
        while (lo <= hi) { val m = (lo + hi) / 2; if (cs[m] <= c) { i = m; lo = m + 1 } else hi = m - 1 }
        val (a, b) = segs[i]
        val off = c - cs[i]
        return if (off <= b - a) a + off else b + (off - (b - a))
    }

    /** 実時刻 [from]〜[to] と重なる区間 (重なる部分だけ)。区間が無ければ [from]〜[to] そのもの */
    fun overlaps(from: Long, to: Long): List<Pair<Long, Long>> =
        if (segs.isEmpty()) listOf(from to to)
        else segs.mapIndexedNotNull { i, (a, b) ->
            val bb = if (i == segs.lastIndex) maxOf(b, to) else b
            val s = maxOf(a, from); val e = minOf(bb, to)
            if (s <= e) s to e else null
        }
}

/** 左右の縦軸の範囲 (null = その軸に項目が無い) */
internal class ChartAxes(val left: Pair<Double, Double>?, val right: Pair<Double, Double>?) {
    companion object {
        /** 項目の並びから、左右それぞれ属する項目の 最小の最小〜最大の最大 を求める */
        fun of(series: List<ChartSeries>): ChartAxes {
            fun range(list: List<ChartSeries>) =
                if (list.isEmpty()) null else list.minOf { it.def.min } to list.maxOf { it.def.max }
            return ChartAxes(range(series.filter { !it.right }), range(series.filter { it.right }))
        }
    }
}

/** 目盛りの間隔の候補 (ミリ秒) */
internal val TIME_STEPS = listOf(
    60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L,
    3_600_000L, 7_200_000L, 10_800_000L, 21_600_000L, 43_200_000L,
    86_400_000L, 172_800_000L, 604_800_000L, 1_209_600_000L, 2_592_000_000L,
)

/** 1 画面の期間 [span] に対して、目盛りが 5 本以上になる一番大きい間隔 */
internal fun timeStep(span: Long): Long = TIME_STEPS.firstOrNull { it * 5 >= span } ?: TIME_STEPS.last()

/** [t] に一番近い記録 */
internal fun nearestSample(pts: List<Sample>, t: Long): Sample? = pts.minByOrNull { abs(it.t - t) }

/**
 * タップした時刻 [cursor] を、一番近い記録の時刻にそろえる (どの項目にも記録が無ければそのまま)
 */
internal fun snapCursor(data: Map<String, List<Sample>>, cursor: Long): Long =
    data.values.mapNotNull { nearestSample(it, cursor) }.minByOrNull { abs(it.t - cursor) }?.t ?: cursor

/**
 * 時刻 [time] での各項目の値。[time] から 1 画面の 1/20 (最低 2 秒) より離れた記録しか無い項目は null
 */
internal fun valuesAt(data: Map<String, List<Sample>>, keys: List<String>, time: Long, span: Long): Map<String, Double?> =
    keys.associateWith { k ->
        nearestSample(data[k].orEmpty(), time)?.takeIf { abs(it.t - time) <= max(span / 20, 2_000L) }?.v?.toDouble()
    }

/** 記録の無い時間とみなす間隔 (記録間隔の 3 倍、1 画面の 1/200、5 秒のうち一番長いもの) */
internal fun gapThreshold(intervalMs: Long, span: Long): Long = maxOf(intervalMs * 3, span / 200, 5_000L)
