package com.obdmeter.ui.gauges

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.obdmeter.core.PidDef
import com.obdmeter.data.GaugeStyle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

object GaugeColors {
    val Background = Color(0xFF0E1116)
    val Card = Color(0xFF161A21)
    val CardBorder = Color(0xFF242A33)
    val Track = Color(0xFF2A303A)
    val Tick = Color(0xFF8A93A0)
    val Text = Color(0xFFE8EAED)
    val SubText = Color(0xFF8A93A0)
    val Accent = Color(0xFF4FC3F7)
    val Warn = Color(0xFFFF5A36)
    val WarnZone = Color(0x66FF5A36)
}

/** メーター 1 個分のカード。スタイルに応じて描き分ける。 */
@Composable
fun GaugeCard(
    def: PidDef,
    value: Double?,
    style: GaugeStyle,
    unsupported: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(GaugeColors.Card, RoundedCornerShape(14.dp))
            .border(1.dp, GaugeColors.CardBorder, RoundedCornerShape(14.dp))
            .padding(8.dp)
    ) {
        val v = rememberDisplayValue(def, if (unsupported) null else value)
        when (style) {
            GaugeStyle.ANALOG -> AnalogGauge(def, v, unsupported, Modifier.fillMaxSize())
            GaugeStyle.BAR -> BarGauge(def, v, unsupported, Modifier.fillMaxSize())
            GaugeStyle.DIGITAL -> DigitalGauge(def, v, unsupported, Modifier.fillMaxSize())
        }
    }
}

/** 表示の更新間隔。通信は 100 回/秒近く値が来るが、人が読める速さに間引く。 */
private const val DISPLAY_INTERVAL_MS = 200L

/**
 * 生の値をそのまま描くと、アイドリングの ±数 rpm の揺れで数字と針が震えて見える。
 * 指数移動平均でならし、[DISPLAY_INTERVAL_MS] ごとに表示用の値を更新し、
 * さらに [PidDef.displayStep] 単位 (回転数なら 10rpm) に丸める。
 */
@Composable
private fun rememberDisplayValue(def: PidDef, value: Double?): Double? {
    val latest by rememberUpdatedState(value)
    var shown by remember(def.key) { mutableStateOf(value) }
    LaunchedEffect(def.key) {
        var ema = Double.NaN
        while (true) {
            val x = latest
            if (x == null) {
                ema = Double.NaN
                shown = null
            } else {
                ema = if (ema.isNaN()) x else ema + (x - ema) * 0.5
                val step = def.displayStep
                shown = if (step > 0) Math.round(ema / step) * step else ema
            }
            delay(DISPLAY_INTERVAL_MS)
        }
    }
    return shown
}

private fun isWarn(def: PidDef, v: Double?): Boolean =
    v != null && ((def.warnAbove != null && v >= def.warnAbove) || (def.warnBelow != null && v <= def.warnBelow))

private fun fraction(def: PidDef, v: Double): Float =
    ((v - def.min) / (def.max - def.min)).toFloat().coerceIn(0f, 1f)

@Composable
private fun animated(def: PidDef, value: Double?): Float {
    // 値が一瞬途切れても針を 0 に戻さず、最後の位置で止めておく (ちらつき防止)
    val last = remember { floatArrayOf(0f) }
    val target = value?.let { fraction(def, it) }?.also { last[0] = it } ?: last[0]
    // 表示値の更新間隔と同じ長さで等速に動かし、次の値へ途切れなくつなぐ
    return animateFloatAsState(target, tween(DISPLAY_INTERVAL_MS.toInt(), easing = LinearEasing), label = "gauge").value
}

// ---------------------------------------------------------------------------
// アナログ (針) メーター
// ---------------------------------------------------------------------------

private const val START_ANGLE = 150f
private const val SWEEP = 240f

@Composable
fun AnalogGauge(def: PidDef, value: Double?, unsupported: Boolean, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val f = animated(def, value)
    val warn = isWarn(def, value)
    Canvas(modifier) {
        val d = min(size.width, size.height * 1.12f)
        val stroke = d * 0.055f
        val radius = d / 2 - stroke
        val center = Offset(size.width / 2, min(size.height / 2 + d * 0.06f, d / 2 + d * 0.04f))
        val arcTopLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)

        // 目盛りの土台
        drawArc(GaugeColors.Track, START_ANGLE, SWEEP, false, arcTopLeft, arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
        // 警告ゾーン
        def.warnAbove?.let { w ->
            val s = fraction(def, w)
            drawArc(GaugeColors.WarnZone, START_ANGLE + SWEEP * s, SWEEP * (1 - s), false, arcTopLeft, arcSize,
                style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        def.warnBelow?.let { w ->
            val s = fraction(def, w)
            drawArc(GaugeColors.WarnZone, START_ANGLE, SWEEP * s, false, arcTopLeft, arcSize,
                style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        // 現在値の弧
        if (value != null) {
            drawArc(if (warn) GaugeColors.Warn else GaugeColors.Accent, START_ANGLE, SWEEP * f, false,
                arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }

        // 目盛りとラベル
        val scale = TickScale.of(def.min, def.max)
        val labelSize = (d * 0.075f).toSp()
        var t = scale.first
        while (t <= def.max + scale.step * 0.001) {
            val a = Math.toRadians((START_ANGLE + SWEEP * fraction(def, t)).toDouble())
            val outer = radius - stroke * 0.9f
            val inner = outer - d * 0.05f
            drawLine(GaugeColors.Tick,
                Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat()),
                Offset(center.x + outer * cos(a).toFloat(), center.y + outer * sin(a).toFloat()),
                strokeWidth = d * 0.012f)
            val labelR = inner - d * 0.065f
            drawCenteredText(tm, scale.label(t),
                Offset(center.x + labelR * cos(a).toFloat(), center.y + labelR * sin(a).toFloat()),
                TextStyle(color = GaugeColors.SubText, fontSize = labelSize))
            t += scale.step
        }

        // 針
        if (value != null) {
            val a = Math.toRadians((START_ANGLE + SWEEP * f).toDouble())
            val len = radius - stroke * 1.2f
            drawLine(if (warn) GaugeColors.Warn else GaugeColors.Text, center,
                Offset(center.x + len * cos(a).toFloat(), center.y + len * sin(a).toFloat()),
                strokeWidth = d * 0.022f, cap = StrokeCap.Round)
        }
        drawCircle(GaugeColors.Text, d * 0.03f, center)

        // 数値・名称
        val valueText = if (unsupported) "非対応" else def.format(value)
        drawCenteredText(tm, valueText, Offset(center.x, center.y + radius * 0.42f),
            TextStyle(color = if (warn) GaugeColors.Warn else GaugeColors.Text,
                fontSize = (d * if (unsupported) 0.1f else 0.15f).toSp(),
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        drawCenteredText(tm, def.unit + scale.unitSuffix, Offset(center.x, center.y + radius * 0.68f),
            TextStyle(color = GaugeColors.SubText, fontSize = (d * 0.065f).toSp()))
        drawCenteredText(tm, def.shortName, Offset(center.x, center.y - radius * 0.38f),
            TextStyle(color = GaugeColors.SubText, fontSize = (d * 0.075f).toSp(), fontWeight = FontWeight.Medium))
    }
}

/** 範囲から見やすい目盛り間隔を決める。1000 以上は ×1000 表記。 */
private data class TickScale(val first: Double, val step: Double, val divisor: Double) {
    val unitSuffix get() = if (divisor > 1) " ×1000" else ""
    fun label(v: Double): String {
        val x = v / divisor
        return if (abs(x - Math.round(x)) < 1e-6) Math.round(x).toString() else "%.1f".format(x)
    }

    companion object {
        fun of(min: Double, max: Double): TickScale {
            val range = max - min
            val raw = range / 8
            val mag = 10.0.pow(floor(log10(raw)))
            val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
            val first = Math.ceil(min / step) * step
            val divisor = if (max(abs(min), abs(max)) >= 1000 && step >= 500) 1000.0 else 1.0
            return TickScale(first, step, divisor)
        }
    }
}

private fun DrawScope.drawCenteredText(tm: TextMeasurer, text: String, center: Offset, style: TextStyle) {
    val layout = tm.measure(text, style)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

// ---------------------------------------------------------------------------
// バーメーター
// ---------------------------------------------------------------------------

@Composable
fun BarGauge(def: PidDef, value: Double?, unsupported: Boolean, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val f = animated(def, value)
    val warn = isWarn(def, value)
    Canvas(modifier) {
        val h = size.height
        val w = size.width
        val unit = min(h, w * 0.5f)
        val nameStyle = TextStyle(color = GaugeColors.SubText, fontSize = (unit * 0.16f).toSp(), fontWeight = FontWeight.Medium)
        val name = tm.measure(def.shortName, nameStyle)
        drawText(name, topLeft = Offset(4f, 0f))

        val valueStyle = TextStyle(
            color = if (warn) GaugeColors.Warn else GaugeColors.Text,
            fontSize = (unit * if (unsupported) 0.2f else 0.34f).toSp(),
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
        )
        val valueLayout = tm.measure(if (unsupported) "非対応" else def.format(value), valueStyle)
        val unitLayout = tm.measure(def.unit, TextStyle(color = GaugeColors.SubText, fontSize = (unit * 0.14f).toSp()))
        val valueY = h * 0.52f - valueLayout.size.height / 2f
        drawText(unitLayout, topLeft = Offset(w - unitLayout.size.width - 4f, valueY + valueLayout.size.height - unitLayout.size.height * 1.15f))
        drawText(valueLayout, topLeft = Offset(w - unitLayout.size.width - 10f - valueLayout.size.width, valueY))

        // バー
        val barH = max(h * 0.12f, 6f)
        val barY = h - barH - h * 0.08f
        val r = CornerRadius(barH / 2, barH / 2)
        drawRoundRect(GaugeColors.Track, Offset(0f, barY), Size(w, barH), r)
        def.warnAbove?.let { drawRect(GaugeColors.WarnZone, Offset(w * fraction(def, it), barY), Size(w * (1 - fraction(def, it)), barH)) }
        def.warnBelow?.let { drawRect(GaugeColors.WarnZone, Offset(0f, barY), Size(w * fraction(def, it), barH)) }
        if (value != null) {
            val color = if (warn) GaugeColors.Warn else GaugeColors.Accent
            if (def.min < 0 && def.max > 0) {
                // 燃調のような ± 値は 0 から伸ばす
                val zero = w * fraction(def, 0.0)
                val x = w * f
                drawRoundRect(color, Offset(min(zero, x), barY), Size(abs(x - zero).coerceAtLeast(3f), barH), r)
                drawLine(GaugeColors.Text, Offset(zero, barY - 4f), Offset(zero, barY + barH + 4f), 2f)
            } else {
                drawRoundRect(color, Offset(0f, barY), Size(w * f, barH), r)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// デジタル (数値) 表示
// ---------------------------------------------------------------------------

@Composable
fun DigitalGauge(def: PidDef, value: Double?, unsupported: Boolean, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val warn = isWarn(def, value)
    Canvas(modifier) {
        val unit = min(size.height, size.width * 0.6f)
        val name = tm.measure(def.shortName,
            TextStyle(color = GaugeColors.SubText, fontSize = (unit * 0.14f).toSp(), fontWeight = FontWeight.Medium))
        drawText(name, topLeft = Offset(4f, 0f))
        drawCenteredText(tm, if (unsupported) "非対応" else def.format(value),
            Offset(size.width / 2, size.height * 0.52f),
            TextStyle(color = if (warn) GaugeColors.Warn else GaugeColors.Text,
                fontSize = (unit * if (unsupported) 0.2f else 0.38f).toSp(),
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        drawCenteredText(tm, def.unit, Offset(size.width / 2, size.height * 0.86f),
            TextStyle(color = GaugeColors.SubText, fontSize = (unit * 0.13f).toSp()))
    }
}
