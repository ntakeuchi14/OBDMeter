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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.obdmeter.core.PidDef
import com.obdmeter.data.GaugeImages
import com.obdmeter.data.GaugeStyle
import com.obdmeter.data.RingStyle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
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
    val Ring = Color(0xFFB0B6BE)
}

/**
 * メーター 1 個の見た目をすべてまとめたもの (保存形式の GaugeDesign から作る)。
 * 角度は Compose の向き (3 時 = 0°、時計回り) に変換済み。
 */
data class GaugeLook(
    val accent: Color = GaugeColors.Accent,
    val startAngle: Float = 150f,
    val sweep: Float = 240f,
    val majorDivisions: Int? = null,
    val minorDivisions: Int = 0,
    val showLabels: Boolean = true,
    val tickRadius: Float = 1f,
    val tickColor: Color = GaugeColors.Tick,
    val labelColor: Color = GaugeColors.SubText,
    val labelSize: Float = 1f,
    val ring: RingStyle = RingStyle.NONE,
    val ringColor: Color = GaugeColors.Ring,
    val showTrack: Boolean = true,
    val showValueArc: Boolean = true,
    val arcWidth: Float = 1f,
    val redZone: ClosedFloatingPointRange<Double>? = null,
    val redZoneColor: Color = GaugeColors.WarnZone,
    val showNeedle: Boolean = true,
    val needleColor: Color = GaugeColors.Text,
    val needleLength: Float = 1f,
    val showValue: Boolean = true,
    val valueColor: Color = GaugeColors.Text,
    val valuePosition: Float = 0.42f,
    val valueSize: Float = 1f,
    val showName: Boolean = true,
    val showUnit: Boolean = true,
    val image: String? = null,
    val imageScale: Float = 1f,
    val showCard: Boolean = true,
)

/** メーター 1 個分。スタイルに応じて描き分ける。 */
@Composable
fun GaugeCard(
    def: PidDef,
    value: Double?,
    style: GaugeStyle,
    unsupported: Boolean,
    look: GaugeLook,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        if (look.showCard) {
            modifier.background(GaugeColors.Card, shape).border(1.dp, GaugeColors.CardBorder, shape).padding(8.dp)
        } else {
            modifier.padding(2.dp)
        }
    ) {
        val v = rememberDisplayValue(def, if (unsupported) null else value)
        val image = rememberGaugeImage(look.image)
        when (style) {
            GaugeStyle.ANALOG -> AnalogGauge(def, v, unsupported, look, image, Modifier.fillMaxSize())
            GaugeStyle.BAR -> BarGauge(def, v, unsupported, look, image, Modifier.fillMaxSize())
            GaugeStyle.DIGITAL -> DigitalGauge(def, v, unsupported, look, image, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun rememberGaugeImage(name: String?): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, name) {
        value = name?.let { GaugeImages.load(context, it) }
    }.value
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

/** 画像を縦横比を保ったまま、[center] を中心に一辺 [box] の正方形に収めて描く */
private fun DrawScope.drawImageFit(image: ImageBitmap, center: Offset, box: Float) {
    val iw = image.width.toFloat()
    val ih = image.height.toFloat()
    val scale = min(box / iw, box / ih)
    val w = (iw * scale).roundToInt()
    val h = (ih * scale).roundToInt()
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset((center.x - w / 2f).roundToInt(), (center.y - h / 2f).roundToInt()),
        dstSize = IntSize(w, h),
    )
}

// ---------------------------------------------------------------------------
// アナログ (針) メーター
// ---------------------------------------------------------------------------

@Composable
fun AnalogGauge(
    def: PidDef,
    value: Double?,
    unsupported: Boolean,
    look: GaugeLook,
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    val f = animated(def, value)
    val warn = isWarn(def, value)
    Canvas(modifier) {
        val d = min(size.width, size.height)
        val center = Offset(size.width / 2, size.height / 2)
        val outer = d / 2

        // 背景画像 (目盛りや針は上から描く)
        image?.let { drawImageFit(it, center, d * look.imageScale) }

        // 外周リング
        val ringWidth = when (look.ring) {
            RingStyle.NONE -> 0f
            RingStyle.LINE -> d * 0.012f
            RingStyle.BOLD -> d * 0.035f
            RingStyle.METAL -> d * 0.05f
        }
        if (ringWidth > 0f) {
            val r = outer - ringWidth / 2
            if (look.ring == RingStyle.METAL) {
                val c = look.ringColor
                val brush = Brush.sweepGradient(
                    listOf(c, Color.White, c.copy(alpha = 0.6f), Color.DarkGray, c, Color.White, c),
                    center,
                )
                drawCircle(brush, r, center, style = Stroke(ringWidth))
            } else {
                drawCircle(look.ringColor, r, center, style = Stroke(ringWidth))
            }
        }

        // 目盛り・弧の基準半径
        val gaugeR = (outer - ringWidth - d * 0.01f) * look.tickRadius
        val stroke = d * 0.045f * look.arcWidth
        val arcR = gaugeR - stroke / 2
        val arcTopLeft = Offset(center.x - arcR, center.y - arcR)
        val arcSize = Size(arcR * 2, arcR * 2)
        val start = look.startAngle
        val sweep = look.sweep

        if (look.showTrack && stroke > 0f) {
            drawArc(GaugeColors.Track, start, sweep, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        look.redZone?.let { z ->
            val s = fraction(def, z.start)
            val e = fraction(def, z.endInclusive)
            if (e > s) {
                drawArc(look.redZoneColor, start + sweep * s, sweep * (e - s), false, arcTopLeft, arcSize,
                    style = Stroke(max(stroke, d * 0.02f), cap = StrokeCap.Butt))
            }
        }
        if (value != null && look.showValueArc && stroke > 0f) {
            drawArc(if (warn) GaugeColors.Warn else look.accent, start, sweep * f, false,
                arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        }

        // 目盛り (主・小) と数字
        val scale = TickScale.of(def.min, def.max, look.majorDivisions)
        val tickOuter = gaugeR - stroke - d * 0.008f
        val majorLen = d * 0.05f
        val minorLen = d * 0.025f
        val labelSize = (d * 0.07f * look.labelSize).toSp()
        val majors = scale.values(def.max)
        for ((i, t) in majors.withIndex()) {
            val a = Math.toRadians((start + sweep * fraction(def, t)).toDouble())
            drawLine(look.tickColor,
                Offset(center.x + (tickOuter - majorLen) * cos(a).toFloat(), center.y + (tickOuter - majorLen) * sin(a).toFloat()),
                Offset(center.x + tickOuter * cos(a).toFloat(), center.y + tickOuter * sin(a).toFloat()),
                strokeWidth = d * 0.012f)
            if (look.showLabels) {
                val labelR = tickOuter - majorLen - d * 0.06f * look.labelSize
                drawCenteredText(tm, scale.label(t),
                    Offset(center.x + labelR * cos(a).toFloat(), center.y + labelR * sin(a).toFloat()),
                    TextStyle(color = look.labelColor, fontSize = labelSize))
            }
            // 小目盛り
            if (look.minorDivisions > 0 && i < majors.lastIndex) {
                val next = majors[i + 1]
                for (k in 1..look.minorDivisions) {
                    val tv = t + (next - t) * k / (look.minorDivisions + 1)
                    val b = Math.toRadians((start + sweep * fraction(def, tv)).toDouble())
                    drawLine(look.tickColor,
                        Offset(center.x + (tickOuter - minorLen) * cos(b).toFloat(), center.y + (tickOuter - minorLen) * sin(b).toFloat()),
                        Offset(center.x + tickOuter * cos(b).toFloat(), center.y + tickOuter * sin(b).toFloat()),
                        strokeWidth = d * 0.006f)
                }
            }
        }

        // 針
        if (value != null && look.showNeedle) {
            val a = Math.toRadians((start + sweep * f).toDouble())
            val len = tickOuter * look.needleLength
            val color = if (warn) GaugeColors.Warn else look.needleColor
            drawLine(color, center,
                Offset(center.x + len * cos(a).toFloat(), center.y + len * sin(a).toFloat()),
                strokeWidth = d * 0.02f, cap = StrokeCap.Round)
            // 反対側に少し出る尾
            drawLine(color, center,
                Offset(center.x - len * 0.12f * cos(a).toFloat(), center.y - len * 0.12f * sin(a).toFloat()),
                strokeWidth = d * 0.03f, cap = StrokeCap.Round)
            drawCircle(color, d * 0.035f, center)
            drawCircle(Color(0xFF20242B), d * 0.015f, center)
        }

        // 名前・数値・単位
        if (look.showName) {
            drawCenteredText(tm, def.shortName, Offset(center.x, center.y - gaugeR * 0.38f),
                TextStyle(color = look.labelColor, fontSize = (d * 0.07f).toSp(), fontWeight = FontWeight.Medium))
        }
        val valueY = center.y + gaugeR * look.valuePosition
        if (look.showValue) {
            val valueText = if (unsupported) "非対応" else def.format(value)
            drawCenteredText(tm, valueText, Offset(center.x, valueY),
                TextStyle(color = if (warn) GaugeColors.Warn else look.valueColor,
                    fontSize = (d * (if (unsupported) 0.09f else 0.13f) * look.valueSize).toSp(),
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        }
        if (look.showUnit) {
            drawCenteredText(tm, def.unit + scale.unitSuffix,
                Offset(center.x, valueY + d * 0.1f * max(look.valueSize, 0.6f)),
                TextStyle(color = look.labelColor, fontSize = (d * 0.055f).toSp()))
        }
    }
}

/** 範囲から見やすい目盛り間隔を決める。1000 以上は ×1000 表記。 */
private data class TickScale(val first: Double, val step: Double, val divisor: Double) {
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

private fun DrawScope.drawCenteredText(tm: TextMeasurer, text: String, center: Offset, style: TextStyle) {
    val layout = tm.measure(text, style)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

// ---------------------------------------------------------------------------
// バーメーター
// ---------------------------------------------------------------------------

@Composable
fun BarGauge(
    def: PidDef,
    value: Double?,
    unsupported: Boolean,
    look: GaugeLook,
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    val f = animated(def, value)
    val warn = isWarn(def, value)
    Canvas(modifier) {
        val h = size.height
        val w = size.width
        image?.let { drawImageFit(it, Offset(w / 2, h / 2), max(w, h) * look.imageScale) }
        val unit = min(h, w * 0.5f)
        if (look.showName) {
            val name = tm.measure(def.shortName,
                TextStyle(color = look.labelColor, fontSize = (unit * 0.16f).toSp(), fontWeight = FontWeight.Medium))
            drawText(name, topLeft = Offset(4f, 0f))
        }

        if (look.showValue) {
            val valueStyle = TextStyle(
                color = if (warn) GaugeColors.Warn else look.valueColor,
                fontSize = (unit * (if (unsupported) 0.2f else 0.34f) * look.valueSize).toSp(),
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            )
            val valueLayout = tm.measure(if (unsupported) "非対応" else def.format(value), valueStyle)
            val unitLayout = tm.measure(if (look.showUnit) def.unit else "",
                TextStyle(color = look.labelColor, fontSize = (unit * 0.14f).toSp()))
            val valueY = h * 0.52f - valueLayout.size.height / 2f
            drawText(unitLayout, topLeft = Offset(w - unitLayout.size.width - 4f, valueY + valueLayout.size.height - unitLayout.size.height * 1.15f))
            drawText(valueLayout, topLeft = Offset(w - unitLayout.size.width - 10f - valueLayout.size.width, valueY))
        }

        // バー
        val barH = max(h * 0.12f * look.arcWidth, 4f)
        val barY = h - barH - h * 0.08f
        val r = CornerRadius(barH / 2, barH / 2)
        if (look.showTrack) drawRoundRect(GaugeColors.Track, Offset(0f, barY), Size(w, barH), r)
        look.redZone?.let { z ->
            val s = fraction(def, z.start)
            val e = fraction(def, z.endInclusive)
            if (e > s) drawRect(look.redZoneColor, Offset(w * s, barY), Size(w * (e - s), barH))
        }
        if (value != null && look.showValueArc) {
            val color = if (warn) GaugeColors.Warn else look.accent
            if (def.min < 0 && def.max > 0) {
                // 燃調のような ± 値は 0 から伸ばす
                val zero = w * fraction(def, 0.0)
                val x = w * f
                drawRoundRect(color, Offset(min(zero, x), barY), Size(abs(x - zero).coerceAtLeast(3f), barH), r)
                drawLine(look.tickColor, Offset(zero, barY - 4f), Offset(zero, barY + barH + 4f), 2f)
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
fun DigitalGauge(
    def: PidDef,
    value: Double?,
    unsupported: Boolean,
    look: GaugeLook,
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    val warn = isWarn(def, value)
    Canvas(modifier) {
        image?.let { drawImageFit(it, Offset(size.width / 2, size.height / 2), max(size.width, size.height) * look.imageScale) }
        val unit = min(size.height, size.width * 0.6f)
        if (look.showName) {
            val name = tm.measure(def.shortName,
                TextStyle(color = look.labelColor, fontSize = (unit * 0.14f).toSp(), fontWeight = FontWeight.Medium))
            drawText(name, topLeft = Offset(4f, 0f))
        }
        // valuePosition は中心からのずれ (アナログと共通の値を流用。既定 0.42 → 中央やや下)
        val cy = size.height * (0.5f + (look.valuePosition - 0.42f) * 0.5f)
        if (look.showValue) {
            drawCenteredText(tm, if (unsupported) "非対応" else def.format(value),
                Offset(size.width / 2, cy),
                TextStyle(color = if (warn) GaugeColors.Warn else look.valueColor,
                    fontSize = (unit * (if (unsupported) 0.2f else 0.38f) * look.valueSize).toSp(),
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        }
        if (look.showUnit) {
            drawCenteredText(tm, def.unit, Offset(size.width / 2, cy + unit * 0.3f * max(look.valueSize, 0.6f)),
                TextStyle(color = look.labelColor, fontSize = (unit * 0.13f).toSp()))
        }
    }
}
