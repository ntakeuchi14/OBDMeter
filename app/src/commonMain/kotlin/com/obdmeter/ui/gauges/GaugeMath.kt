package com.obdmeter.ui.gauges

import com.obdmeter.util.format

import com.obdmeter.core.PidDef
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

// メーターの描画で使う計算 (描画はしない)

/** 表示の更新間隔。通信は 100 回/秒近く値が来るが、人が読める速さに間引く。 */
internal const val DISPLAY_INTERVAL_MS = 200L

/** 表示値を更新する間隔 (なめらかさに応じて 60〜320ms。0.5 で従来の 200ms) */
internal fun displayIntervalMs(smoothing: Float): Long = (60 + 280 * smoothing.coerceIn(0f, 1f)).toLong()

/** 表示値をならす割合 (指数移動平均の係数)。なめらかさ 0 → 1 (ならさない)、0.5 → 約 0.58、1 → 0.15 */
internal fun smoothingAlpha(smoothing: Float): Double = (1.0 - smoothing * 0.85).coerceIn(0.15, 1.0)

internal fun isWarn(def: PidDef, v: Double?): Boolean =
    v != null && ((def.warnAbove != null && v >= def.warnAbove) || (def.warnBelow != null && v <= def.warnBelow))

internal fun fraction(def: PidDef, v: Double): Float =
    ((v - def.min) / (def.max - def.min)).toFloat().coerceIn(0f, 1f)

/** 範囲から見やすい目盛り間隔を決める。1000 以上は ×1000 表記。 */
private fun isWhole(x: Double) = abs(x - kotlin.math.round(x)) < 1e-6

internal data class TickScale(val first: Double, val step: Double, val divisor: Double) {
    val unitSuffix get() = if (divisor > 1) " ×1000" else ""

    fun label(v: Double): String {
        val x = v / divisor
        return if (abs(x - com.obdmeter.util.jround(x)) < 1e-6) com.obdmeter.util.jround(x).toString() else "%.1f".format(x)
    }

    fun values(max: Double): List<Double> {
        val out = mutableListOf<Double>()
        var t = first
        while (t <= max + step * 0.001 && out.size < 100) { out += t; t += step }
        return out
    }

    companion object {
        fun of(min: Double, max: Double, divisions: Int? = null): TickScale {
            val range = max - min
            if (divisions != null && divisions > 0) {
                val step = range / divisions
                val divisor = if (max(abs(min), abs(max)) >= 1000 && step >= 500) 1000.0 else 1.0
                return TickScale(min, step, divisor)
            }
            val raw = range / 8
            val mag = 10.0.pow(floor(log10(raw)))
            // 数字に小数点が出ない刻み (1・2・5 の 10 の累乗倍) を優先する。区間が 2 つ以上できるものだけ (範囲が狭すぎるときは小数の刻み)
            for (m in listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0)) {
                val step = m * mag
                if (step < raw * 0.999 || range / step < 1.999) continue
                val divisor = if (max(abs(min), abs(max)) >= 1000 && step >= 500) 1000.0 else 1.0
                val first = kotlin.math.ceil(min / step - 1e-9) * step
                if (isWhole(step / divisor) && isWhole(first / divisor)) return TickScale(first, step, divisor)
            }
            val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
            val first = kotlin.math.ceil(min / step) * step
            val divisor = if (max(abs(min), abs(max)) >= 1000 && step >= 500) 1000.0 else 1.0
            return TickScale(first, step, divisor)
        }
    }
}

/** 消えている 7 セグ表示の文字列 (範囲の最大の桁数で、数字を 8 にしたもの。マイナス記号の分は足さない) */
internal fun ghostText(def: PidDef): String = def.digitTemplate().replace('0', '8')

/**
 * 目盛りの可変間隔。[pairs] は (値の割合 u, 目盛り上の位置 p) を平たく並べたもの (両端 (0,0)・(1,1) を含み、u・p とも増える順)。
 * 点と点の間は直線でつなぐ
 */
internal object ScaleWarp {
    /** 保存した点 (値, 位置) を [min]〜[max] の割合にして並べる。使える点が無ければ null (等間隔) */
    fun pairs(min: Double, max: Double, points: List<com.obdmeter.data.ScalePoint>?): List<Float>? {
        if (points.isNullOrEmpty() || max <= min) return null
        val out = mutableListOf(0f, 0f)
        for (pt in points.sortedBy { it.value }) {
            val u = ((pt.value - min) / (max - min)).toFloat()
            val p = pt.pos
            if (u <= out[out.size - 2] + 1e-4f || u >= 1f - 1e-4f) continue
            if (p <= out[out.size - 1] + 1e-4f || p >= 1f - 1e-4f) continue
            out += u; out += p
        }
        if (out.size == 2) return null
        out += 1f; out += 1f
        // 全部が等間隔の線の上なら null
        if ((0 until out.size / 2).all { abs(out[it * 2] - out[it * 2 + 1]) < 1e-4f }) return null
        return out
    }

    /** 値の割合 → 目盛り上の位置 */
    fun warp(pairs: List<Float>?, u: Float): Float = map(pairs, u, 0, 1)

    /** 目盛り上の位置 → 値の割合 */
    fun unwarp(pairs: List<Float>?, p: Float): Float = map(pairs, p, 1, 0)

    private fun map(pairs: List<Float>?, x: Float, from: Int, to: Int): Float {
        if (pairs == null) return x
        val c = x.coerceIn(0f, 1f)
        val n = pairs.size / 2
        for (i in 0 until n - 1) {
            val x0 = pairs[i * 2 + from]; val x1 = pairs[i * 2 + 2 + from]
            if (c <= x1 || i == n - 2) {
                val y0 = pairs[i * 2 + to]; val y1 = pairs[i * 2 + 2 + to]
                return if (x1 - x0 < 1e-9f) y0 else y0 + (y1 - y0) * (c - x0) / (x1 - x0)
            }
        }
        return c
    }

    /** アナログメーターの主目盛りの値 (最大値が切れ目に来ないときは最大値も足す) */
    fun majors(min: Double, max: Double, divisions: Int?): List<Double> {
        val scale = TickScale.of(min, max, divisions)
        val regular = scale.values(max)
        val endExtra = regular.isNotEmpty() && max - regular.last() > scale.step * 0.01
        return if (endExtra) regular + max else regular
    }

    /** 区間の境目 (最小・主目盛り・最大) の値 */
    fun bounds(min: Double, max: Double, divisions: Int?): List<Double> =
        // -0.0 は 0.0 にそろえる (0 の主目盛りが -0.0 になることがある)
        (listOf(min) + majors(min, max, divisions) + max).map { it + 0.0 }.distinct().filter { it in min..max }.sorted()
            .fold(mutableListOf<Double>()) { acc, v -> if (acc.isEmpty() || v - acc.last() > (max - min) * 1e-6) acc += v; acc }

    /** 区間ごとの幅の比率 (等間隔 = 1) */
    fun weights(min: Double, max: Double, bounds: List<Double>, points: List<com.obdmeter.data.ScalePoint>?): List<Float> {
        val pr = pairs(min, max, points)
        return (0 until bounds.size - 1).map { i ->
            val u0 = ((bounds[i] - min) / (max - min)).toFloat(); val u1 = ((bounds[i + 1] - min) / (max - min)).toFloat()
            val du = u1 - u0
            if (du <= 0f) 1f else (warp(pr, u1) - warp(pr, u0)) / du
        }
    }

    /** 区間ごとの幅の比率から点を作る (境目ごとに 1 点。全部 1 なら null = 等間隔) */
    fun fromWeights(min: Double, max: Double, bounds: List<Double>, weights: List<Float>): List<com.obdmeter.data.ScalePoint>? {
        val du = (0 until bounds.size - 1).map { ((bounds[it + 1] - bounds[it]) / (max - min)).toFloat() }
        val w = weights.map { it.coerceIn(0.05f, 20f) }
        val total = du.indices.sumOf { (du[it] * w[it]).toDouble() }.toFloat()
        if (total <= 0f || w.all { abs(it - 1f) < 1e-3f }) return null
        var acc = 0f
        val out = mutableListOf<com.obdmeter.data.ScalePoint>()
        for (i in 0 until bounds.size - 2) {
            acc += du[i] * w[i] / total
            out += com.obdmeter.data.ScalePoint(bounds[i + 1], acc)
        }
        return out
    }

    /** 境目の位置 (0〜1) の並びから点を作る (最初と最後は 0・1) */
    fun fromPositions(bounds: List<Double>, pos: List<Float>): List<com.obdmeter.data.ScalePoint> =
        (1 until bounds.size - 1).map { com.obdmeter.data.ScalePoint(bounds[it], pos[it]) }

    /** 幅の比率を手入力用の文字にする ("0.6, 0.8, 2, 2") */
    fun formatWeights(w: List<Float>): String = w.joinToString(", ") { v ->
        com.obdmeter.util.format("%.2f", v.toDouble()).trimEnd('0').trimEnd('.')
    }

    /** 手入力した幅の比率 (カンマ・読点・空白区切り) を読む。区間の数 [n] と合わない・0 以下・数でないときは null */
    fun parseWeights(text: String, n: Int): List<Float>? {
        val parts = text.split(',', '、', '，', ' ', '　', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != n) return null
        val w = parts.map { it.toFloatOrNull() ?: return null }
        return if (w.all { it > 0f && it.isFinite() }) w else null
    }

    /** よく使う形 (区間の中央の位置 x (0〜1) → 幅の比率) */
    enum class Preset(val label: String, val weight: (Float) -> Float) {
        MIDDLE("中ほどを広く", { x -> 0.6f + 1.6f * kotlin.math.exp(-((x - 0.4f) / 0.22f).let { it * it }) }),
        LOW("低い側を広く", { x -> 2f - 1.5f * x }),
        HIGH_NARROW("高い側を狭く", { x -> if (x < 0.65f) 1.3f else 0.55f }),
    }
}
