package com.obdmeter.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * アナログ: 針の中心をずらしたときに、目盛りが文字盤 (リングの内側) から見切れないような
 * 目盛りの位置 ([GaugeDesign.tickRadius]) と縦横比 ([GaugeDesign.scaleRoundness]) を探す (画面は使わない)。
 * 長さはすべてメーターの直径 = 1 とした値
 */
object ScaleFit {
    /** 外周リングの幅 (直径に対する比率。描画側の ringWidthFor と同じ) */
    fun ringWidth(d: GaugeDesign): Float = when (d.ring) {
        RingStyle.NONE -> 0f
        RingStyle.LINE -> 0.012f
        RingStyle.BOLD -> 0.035f
        RingStyle.METAL -> 0.05f
    } * d.ringThickness.coerceIn(0.2f, 4f)

    /**
     * 開始〜終了角度 (12 時 = 0°、時計回り) の範囲の目盛りを、文字盤の内側 [margin] の割合までに収めたうえで、
     * 目盛りが針の中心からなるべく遠く (大きく) なる縦横比と目盛りの位置を返す。
     * 収まらないとき (針の中心が文字盤の外など) は null
     */
    fun fit(d: GaugeDesign, margin: Float = 0.95f): Pair<Float, Float>? {
        val base = 0.5f - ringWidth(d) - 0.01f
        if (base <= 0f) return null
        val limit = base * margin
        val px = d.pivotX.toDouble()
        val py = d.pivotY.toDouble()
        if (px * px + py * py >= limit * limit) return null
        val n = 24
        val angles = (0..n).map { i ->
            val deg = d.startAngle + (d.endAngle - d.startAngle) * i / n - 90.0
            deg * PI / 180.0
        }
        var best: Pair<Float, Float>? = null
        var bestScore = -1.0
        var qi = 30
        while (qi <= 150) {
            val q = qi / 100.0
            // 各角度で、楕円の点が文字盤の内側に収まる最大の横の半径
            var r = Double.MAX_VALUE
            for (a in angles) {
                val vx = cos(a)
                val vy = q * sin(a)
                val vv = vx * vx + vy * vy
                val pv = px * vx + py * vy
                val disc = pv * pv - vv * (px * px + py * py - limit * limit)
                if (disc < 0) { r = 0.0; break }
                r = minOf(r, (-pv + sqrt(disc)) / vv)
            }
            val tick = (r / base).coerceAtMost(2.0)
            // 目盛りの点の、針の中心からの平均の距離 (大きいほど目盛りが広く読みやすい)
            val score = angles.sumOf { a -> tick * base * sqrt(cos(a) * cos(a) + q * q * sin(a) * sin(a)) } / angles.size
            if (score > bestScore + 1e-9) {
                bestScore = score
                best = tick.toFloat() to q.toFloat()
            }
            qi++
        }
        return best?.takeIf { it.first >= 0.3f }
    }
}
