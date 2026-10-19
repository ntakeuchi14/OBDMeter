package com.obdmeter.ui.gauges

import com.obdmeter.core.PidDef
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

// メーターの描画で使う計算 (描画はしない)

/** 表示の更新間隔。通信は 100 回/秒近く値が来るが、人が読める速さに間引く。 */
private const val DISPLAY_INTERVAL_MS = 200L

/** 表示値を更新する間隔 (なめらかさに応じて 60〜320ms。0.5 で従来の 200ms) */
internal fun displayIntervalMs(smoothing: Float): Long = (60 + 280 * smoothing.coerceIn(0f, 1f)).toLong()

/** 表示値をならす割合 (指数移動平均の係数)。なめらかさ 0 → 1 (ならさない)、0.5 → 約 0.58、1 → 0.15 */
internal fun smoothingAlpha(smoothing: Float): Double = (1.0 - smoothing * 0.85).coerceIn(0.15, 1.0)

internal fun isWarn(def: PidDef, v: Double?): Boolean =
    v != null && ((def.warnAbove != null && v >= def.warnAbove) || (def.warnBelow != null && v <= def.warnBelow))

internal fun fraction(def: PidDef, v: Double): Float =
    ((v - def.min) / (def.max - def.min)).toFloat().coerceIn(0f, 1f)

/** 範囲から見やすい目盛り間隔を決める。1000 以上は ×1000 表記。 */
internal data class TickScale(val first: Double, val step: Double, val divisor: Double) {
    val unitSuffix get() = if (divisor > 1) " ×1000" else ""

    fun label(v: Double): String {
        val x = v / divisor
        return if (abs(x - Math.round(x)) < 1e-6) Math.round(x).toString() else "%.1f".format(x)
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
            val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
            val first = Math.ceil(min / step) * step
            val divisor = if (max(abs(min), abs(max)) >= 1000 && step >= 500) 1000.0 else 1.0
            return TickScale(first, step, divisor)
        }
    }
}

/** 消えている 7 セグ表示の文字列 (範囲の最小・最大の桁数で、数字を 8 にしたもの) */
internal fun ghostText(def: PidDef): String {
    val a = def.format(def.min)
    val b = def.format(def.max)
    val t = if (a.length > b.length) a else b
    return t.map { if (it.isDigit() || it == '-') '8' else it }.joinToString("")
}
