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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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
import com.obdmeter.core.Condition
import com.obdmeter.core.PidDef
import com.obdmeter.data.FontEntry
import com.obdmeter.data.GaugeFonts
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
    /** 補足説明の文字色。暗い背景でも読める明るさにする */
    val SubText = Color(0xFFB3BAC5)
    val Accent = Color(0xFF4FC3F7)
    val Warn = Color(0xFFFF5A36)
    val WarnZone = Color(0x66FF5A36)
    val Ring = Color(0xFFB0B6BE)
    val Peak = Color(0xFFFFCA28)
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
    val needleWidth: Float = 1f,
    val needleTail: Float = 0.12f,
    val knobColor: Color? = null,
    val knobSize: Float = 1f,
    val ringThickness: Float = 1f,
    val ringDepth: Float = 0.6f,
    val ringInner: Float = 0.4f,
    val valueOffsetX: Float = 0f,
    val imageOffsetX: Float = 0f,
    val imageOffsetY: Float = 0f,
    /** 文字盤の背景色 (背景画像が無いときだけ塗る) */
    val faceColor: Color? = null,
    val faceScale: Float = 1f,
    /** 名前・単位の位置のずれ (メーターの大きさに対する比率) と色 */
    val nameOffsetX: Float = 0f,
    val nameOffsetY: Float = 0f,
    val nameColor: Color = GaugeColors.SubText,
    val unitOffsetX: Float = 0f,
    val unitOffsetY: Float = 0f,
    val unitColor: Color = GaugeColors.SubText,
    /** ランプの点灯条件 (null = 警告値で点灯) */
    val lampCondition: Condition? = null,
    val lampOnColor: Color = GaugeColors.Warn,
    val lampOffColor: Color = Color(0xFF2A2F36),
    /** 値テーブル */
    val tableMaxRows: Int = 5,
    val tableValueBg: Color? = null,
    val tableValueImage: String? = null,
    val tableNameRatio: Float = 0.4f,
    val tableUnitRatio: Float = 0.2f,
    val tableUnitInline: Boolean = false,
    val tableNameInline: Boolean = false,
    val tableNameSize: Float = 1f,
    val tableUnitSize: Float = 1f,
    val tableMarginBottom: Float = 0f,
    val tableMarginRight: Float = 0f,
    /** 折れ線グラフの 1 画面の期間 (ミリ秒) */
    val chartSpanMs: Long = 3_600_000L,
    val barAngle: Float = 0f,
    val barSegments: Int = 0,
    val barSegmentGap: Float = 0.25f,
    val chartIntervalMs: Long = 60_000L,
    val chartSkipGaps: Boolean = true,
    /** 折れ線グラフの線の色 (項目の順。足りない分は自動) */
    val chartColors: List<Color?> = emptyList(),
    val showPeak: Boolean = false,
    val peakColor: Color = GaugeColors.Peak,
    val showPeakValue: Boolean = false,
    val showValue: Boolean = true,
    val valueColor: Color = GaugeColors.Text,
    val valuePosition: Float = 0.42f,
    val valueSize: Float = 1f,
    val showName: Boolean = true,
    val showUnit: Boolean = true,
    val image: String? = null,
    val imageScale: Float = 1f,
    val showCard: Boolean = true,
    /** 取り込んだフォントの id (null = 既定) */
    val valueFont: String? = null,
    val labelFont: String? = null,
)

/** 取り込んだフォントの一覧 (id → 項目)。アプリの最上位で渡す */
val LocalFontLibrary = staticCompositionLocalOf<Map<String, FontEntry>> { emptyMap() }

/** 描画に使うフォント。value は数値、label は目盛りの数字・名前・単位 */
class GaugeFontSet(val value: FontFamily, val label: FontFamily?)

@Composable
fun rememberGaugeFonts(look: GaugeLook): GaugeFontSet {
    val lib = LocalFontLibrary.current
    val context = LocalContext.current
    return remember(look.valueFont, look.labelFont, lib) {
        fun resolve(id: String?) = id?.let { lib[it] }?.let { GaugeFonts.family(context, it) }
        GaugeFontSet(resolve(look.valueFont) ?: FontFamily.Monospace, resolve(look.labelFont))
    }
}

/** メーター 1 個分。スタイルに応じて描き分ける。 */
@Composable
fun GaugeCard(
    def: PidDef,
    value: Double?,
    style: GaugeStyle,
    unsupported: Boolean,
    look: GaugeLook,
    modifier: Modifier = Modifier,
    /** 値が変わるとピーク値をリセットする (メーターの長押しで増やす) */
    peakResetKey: Int = 0,
    /** 折れ線グラフの項目 (空なら [def] だけ) */
    series: List<ChartSeries> = emptyList(),
    /** 折れ線グラフをドラッグで操作できるか (編集中・フロート表示では false) */
    interactive: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    // ピーク値 (生の値で記録する。表示用のならし前の値)
    val peakHolder = remember(def.key, peakResetKey, def.min, def.max) { doubleArrayOf(Double.NaN) }
    if (look.showPeak && value != null && !unsupported && (peakHolder[0].isNaN() || value > peakHolder[0])) {
        peakHolder[0] = value
    }
    val peak = peakHolder[0].takeIf { look.showPeak && !it.isNaN() }
    Box(
        if (look.showCard) {
            modifier.background(GaugeColors.Card, shape).border(1.dp, GaugeColors.CardBorder, shape).padding(8.dp)
        } else {
            modifier.padding(2.dp)
        }
    ) {
        val v = rememberDisplayValue(def, if (unsupported) null else value)
        val image = rememberGaugeImage(look.image)
        val fonts = rememberGaugeFonts(look)
        when (style) {
            GaugeStyle.ANALOG -> AnalogGauge(def, v, unsupported, look, image, Modifier.fillMaxSize(), peak, fonts)
            GaugeStyle.BAR -> BarGauge(def, v, unsupported, look, image, Modifier.fillMaxSize(), peak, fonts)
            GaugeStyle.DIGITAL -> DigitalGauge(def, v, unsupported, look, image, Modifier.fillMaxSize(), peak, fonts)
            // ランプは条件判定をならし前の値で行う
            GaugeStyle.LAMP -> LampGauge(def, if (unsupported) null else value, unsupported, look, image, Modifier.fillMaxSize(), fonts)
            GaugeStyle.TABLE -> TableGauge(
                series.ifEmpty { listOf(ChartSeries(def, value)) }, look, fonts, Modifier.fillMaxSize(),
            )
            GaugeStyle.CHART -> ChartGauge(
                series.ifEmpty { listOf(ChartSeries(def, value)) }, look, fonts, interactive, Modifier.fillMaxSize(),
            )
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

internal fun isWarn(def: PidDef, v: Double?): Boolean =
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
internal fun DrawScope.drawImageFit(image: ImageBitmap, center: Offset, box: Float) {
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
    peak: Double? = null,
    fonts: GaugeFontSet = GaugeFontSet(FontFamily.Monospace, null),
) {
    val tm = rememberTextMeasurer()
    val f = animated(def, value)
    val warn = isWarn(def, value)
    Canvas(modifier) {
        val d = min(size.width, size.height)
        val center = Offset(size.width / 2, size.height / 2)
        val outer = d / 2

        val ringWidth = ringWidthFor(look, d)

        // 背景色で文字盤を塗り、その上に背景画像 (透過した画像なら背景色が透けて見える)。目盛りや針は上から描く
        look.faceColor?.let { c ->
            val fc = if (image != null) Offset(center.x + d * look.imageOffsetX, center.y + d * look.imageOffsetY) else center
            drawCircle(c, (outer - ringWidth / 2) * look.faceScale, fc)
        }
        if (image != null) {
            drawImageFit(image, Offset(center.x + d * look.imageOffsetX, center.y + d * look.imageOffsetY), d * look.imageScale)
        }

        // 外周リング
        if (ringWidth > 0f) drawRing(look, center, outer, ringWidth)

        // 目盛り・弧の基準半径
        val gaugeR = (outer - ringWidth - d * 0.01f) * look.tickRadius
        val rawStroke = d * 0.045f * look.arcWidth
        // 現在値の弧が無い (OFF か太さ 0) ときは、レッドゾーンを目盛りの範囲の外側寄せに描く
        val arcHidden = !look.showValueArc || rawStroke <= 0f
        // 目盛りの外側に弧の場所を空けるのは、弧か背景の弧を描くときだけ
        val stroke = if (arcHidden && !look.showTrack) 0f else rawStroke
        val arcR = gaugeR - stroke / 2
        val arcTopLeft = Offset(center.x - arcR, center.y - arcR)
        val arcSize = Size(arcR * 2, arcR * 2)
        val start = look.startAngle
        val sweep = look.sweep

        if (look.showTrack && stroke > 0f) {
            drawArc(GaugeColors.Track, start, sweep, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        // 目盛りの位置 (レッドゾーンを内側に描くときにも使う)
        val tickOuter = gaugeR - stroke - d * 0.008f
        val majorLen = d * 0.05f
        val minorLen = d * 0.025f
        val zoneInsideW = d * 0.025f
        look.redZone?.let { z ->
            val s = fraction(def, z.start)
            val e = fraction(def, z.endInclusive)
            if (e > s) {
                if (arcHidden) {
                    // 目盛りの範囲内の外側寄せに帯で描く (目盛りは上から重ねて描く)
                    val zr = tickOuter - zoneInsideW / 2
                    drawArc(look.redZoneColor, start + sweep * s, sweep * (e - s), false,
                        Offset(center.x - zr, center.y - zr), Size(zr * 2, zr * 2),
                        style = Stroke(zoneInsideW, cap = StrokeCap.Butt))
                } else {
                    drawArc(look.redZoneColor, start + sweep * s, sweep * (e - s), false, arcTopLeft, arcSize,
                        style = Stroke(max(stroke, d * 0.02f), cap = StrokeCap.Butt))
                }
            }
        }
        if (value != null && look.showValueArc && stroke > 0f) {
            drawArc(if (warn) GaugeColors.Warn else look.accent, start, sweep * f, false,
                arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        }

        // 目盛り (主・小) と数字
        val scale = TickScale.of(def.min, def.max, look.majorDivisions)
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
                    TextStyle(color = look.labelColor, fontFamily = fonts.label, fontSize = labelSize))
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

        // ピーク値の印 (目盛りの外側から内側に向いた三角形)
        if (peak != null) {
            val pa = Math.toRadians((start + sweep * fraction(def, peak)).toDouble())
            val ca = cos(pa).toFloat()
            val sa = sin(pa).toFloat()
            val tip = gaugeR - stroke - d * 0.01f
            val base = gaugeR + d * 0.005f
            val half = d * 0.022f
            val path = Path().apply {
                moveTo(center.x + tip * ca, center.y + tip * sa)
                lineTo(center.x + base * ca - half * sa, center.y + base * sa + half * ca)
                lineTo(center.x + base * ca + half * sa, center.y + base * sa - half * ca)
                close()
            }
            drawPath(path, look.peakColor)
        }

        // 針 (根元が太く先が細い形) と中心のノブ
        if (value != null && look.showNeedle) {
            val a = Math.toRadians((start + sweep * f).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val len = tickOuter * look.needleLength
            val tail = len * look.needleTail
            val baseW = d * 0.016f * look.needleWidth
            val tipW = baseW * 0.35f
            val color = if (warn) GaugeColors.Warn else look.needleColor
            val needle = Path().apply {
                // 尾の端 → 根元 → 先端 → 根元 → 尾の端 (針の向きに垂直な方向へ幅を取る)
                moveTo(center.x - tail * ca - baseW * sa, center.y - tail * sa + baseW * ca)
                lineTo(center.x + len * ca - tipW * sa, center.y + len * sa + tipW * ca)
                lineTo(center.x + len * ca + tipW * sa, center.y + len * sa - tipW * ca)
                lineTo(center.x - tail * ca + baseW * sa, center.y - tail * sa - baseW * ca)
                close()
            }
            drawPath(needle, color)
        }
        if (look.showNeedle && look.knobSize > 0f) {
            val kr = d * 0.035f * look.knobSize
            val kc = look.knobColor ?: look.needleColor
            drawCircle(kc, kr, center)
            // ノブの立体感 (左上が明るく右下が暗い)
            drawCircle(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                    start = Offset(center.x - kr, center.y - kr), end = Offset(center.x + kr, center.y + kr),
                ),
                kr, center,
            )
            drawCircle(Color(0xFF20242B), kr * 0.4f, center)
        }

        // 名前・数値・単位
        if (look.showName) {
            drawCenteredText(tm, def.shortName,
                Offset(center.x + d * look.nameOffsetX, center.y - gaugeR * 0.38f + d * look.nameOffsetY),
                TextStyle(color = look.nameColor, fontFamily = fonts.label, fontSize = (d * 0.07f).toSp(), fontWeight = FontWeight.Medium))
        }
        val valueY = center.y + gaugeR * look.valuePosition
        val valueX = center.x + gaugeR * look.valueOffsetX
        if (look.showValue) {
            val valueText = if (unsupported) "非対応" else def.format(value)
            drawCenteredText(tm, valueText, Offset(valueX, valueY),
                TextStyle(color = if (warn) GaugeColors.Warn else look.valueColor,
                    fontSize = (d * (if (unsupported) 0.09f else 0.13f) * look.valueSize).toSp(),
                    fontWeight = FontWeight.Bold, fontFamily = fonts.value))
        }
        if (look.showUnit) {
            drawCenteredText(tm, def.unitText + scale.unitSuffix,
                Offset(valueX + d * look.unitOffsetX, valueY + d * 0.1f * max(look.valueSize, 0.6f) + d * look.unitOffsetY),
                TextStyle(color = look.unitColor, fontFamily = fonts.label, fontSize = (d * 0.055f).toSp()))
        }
        if (peak != null && look.showPeakValue) {
            drawCenteredText(tm, "MAX " + def.format(peak),
                Offset(valueX, valueY + d * 0.17f * max(look.valueSize, 0.6f)),
                TextStyle(color = look.peakColor, fontSize = (d * 0.05f).toSp(), fontFamily = fonts.value))
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

internal fun DrawScope.drawCenteredText(tm: TextMeasurer, text: String, center: Offset, style: TextStyle) {
    val layout = tm.measure(text, style)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/** [drawCenteredText] と同じだが、幅が [maxWidth] を超えるときは文字を縮める */
internal fun DrawScope.drawCenteredTextFit(tm: TextMeasurer, text: String, center: Offset, style: TextStyle, maxWidth: Float) {
    var layout = tm.measure(text, style)
    if (layout.size.width > maxWidth && maxWidth > 0f) {
        layout = tm.measure(text, style.copy(fontSize = style.fontSize * (maxWidth / layout.size.width)))
    }
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
    peak: Double? = null,
    fonts: GaugeFontSet = GaugeFontSet(FontFamily.Monospace, null),
) {
    val tm = rememberTextMeasurer()
    val f = animated(def, value)
    val warn = isWarn(def, value)
    Canvas(modifier) {
        val h = size.height
        val w = size.width
        drawFace(look)
        image?.let { drawImageFit(it, Offset(w / 2 + w * look.imageOffsetX, h / 2 + h * look.imageOffsetY), max(w, h) * look.imageScale) }
        val unit = min(h, w * 0.5f)
        if (look.showName) {
            val name = tm.measure(def.shortName,
                TextStyle(color = look.nameColor, fontFamily = fonts.label, fontSize = (unit * 0.16f).toSp(), fontWeight = FontWeight.Medium))
            drawText(name, topLeft = Offset(4f + w * look.nameOffsetX, h * look.nameOffsetY))
        }

        if (look.showValue) {
            val valueStyle = TextStyle(
                color = if (warn) GaugeColors.Warn else look.valueColor,
                fontSize = (unit * (if (unsupported) 0.2f else 0.34f) * look.valueSize).toSp(),
                fontWeight = FontWeight.Bold, fontFamily = fonts.value,
            )
            val valueLayout = tm.measure(if (unsupported) "非対応" else def.format(value), valueStyle)
            val unitLayout = tm.measure(if (look.showUnit) def.unitText else "",
                TextStyle(color = look.unitColor, fontFamily = fonts.label, fontSize = (unit * 0.14f).toSp()))
            // 縦横の位置調整 (アナログと共通の設定。既定は右寄せ・中央)
            val valueY = h * (0.52f + (look.valuePosition - 0.42f) * 0.5f) - valueLayout.size.height / 2f
            val dx = w * look.valueOffsetX * 0.5f
            drawText(unitLayout, topLeft = Offset(
                w - unitLayout.size.width - 4f + dx + w * look.unitOffsetX,
                valueY + valueLayout.size.height - unitLayout.size.height * 1.15f + h * look.unitOffsetY,
            ))
            drawText(valueLayout, topLeft = Offset(w - unitLayout.size.width - 10f - valueLayout.size.width + dx, valueY))
        }

        // バー (角度を付けるときはメーターの中央を軸に回し、枠に収まる長さにする)
        val barHidden = !look.showValueArc || look.arcWidth <= 0f
        val barH = if (look.arcWidth <= 0f) 0f else max(h * 0.12f * look.arcWidth, 4f)
        // バーが無いときのレッドゾーンは細い帯で描く
        val zoneH = if (barHidden) max(h * 0.04f, 3f) else barH
        val thick = max(barH, zoneH)
        val angle = look.barAngle
        val rad = Math.toRadians(angle.toDouble())
        val pivot = if (angle == 0f) Offset(w / 2, h - thick / 2 - h * 0.08f) else Offset(w / 2, h / 2)
        val len = if (angle == 0f) w else {
            val c = abs(cos(rad)).toFloat()
            val sn = abs(sin(rad)).toFloat()
            // 太さの分も含めて枠に収まる長さ
            val byW = if (c > 1e-3f) (w - thick * sn) / c else Float.MAX_VALUE
            val byH = if (sn > 1e-3f) (h - thick * c) / sn else Float.MAX_VALUE
            min(byW, byH) * 0.94f
        }.coerceAtLeast(4f)
        val segs = look.barSegments
        val gap = if (segs > 0) len / segs * look.barSegmentGap else 0f
        val r = if (segs > 0) CornerRadius(min(barH / 6, 4f), min(barH / 6, 4f)) else CornerRadius(barH / 2, barH / 2)
        /** [from]〜[to] (0〜1) の範囲を、破線ならマスごとに、実線なら 1 本で塗る (バーの座標: 左端 0) */
        fun DrawScope.fillRange(color: Color, from: Float, to: Float, top: Float, height: Float, minWidth: Float = 0f) {
            val lo = min(from, to); val hi = max(from, to)
            if (segs <= 0) {
                drawRoundRect(color, Offset(len * lo, top), Size((len * (hi - lo)).coerceAtLeast(minWidth), height), r)
            } else {
                val cell = len / segs
                for (i in 0 until segs) {
                    // マスの中心が範囲に入っていれば点ける
                    val mid = (i + 0.5f) / segs
                    if (mid < lo || mid > hi) continue
                    drawRoundRect(color, Offset(cell * i + gap / 2, top), Size(cell - gap, height), r)
                }
            }
        }
        withTransform({
            translate(pivot.x - len / 2, pivot.y - thick / 2)
            rotate(angle, Offset(len / 2, thick / 2))
        }) {
            val barTop = (thick - barH) / 2
            if (look.showTrack && barH > 0f) fillRange(GaugeColors.Track, 0f, 1f, barTop, barH)
            look.redZone?.let { z ->
                val s0 = fraction(def, z.start)
                val e0 = fraction(def, z.endInclusive)
                if (e0 > s0) {
                    if (segs > 0 && !barHidden) fillRange(look.redZoneColor, s0, e0, (thick - zoneH) / 2, zoneH)
                    else drawRect(look.redZoneColor, Offset(len * s0, (thick - zoneH) / 2), Size(len * (e0 - s0), zoneH))
                }
            }
            if (value != null && !barHidden) {
                val color = if (warn) GaugeColors.Warn else look.accent
                if (def.min < 0 && def.max > 0) {
                    // 燃調のような ± 値は 0 から伸ばす
                    val zero = fraction(def, 0.0)
                    fillRange(color, zero, f, barTop, barH, minWidth = 3f)
                    drawLine(look.tickColor, Offset(len * zero, barTop - 4f), Offset(len * zero, barTop + barH + 4f), 2f)
                } else {
                    fillRange(color, 0f, f, barTop, barH)
                }
            }
            if (peak != null) {
                val px = len * fraction(def, peak)
                drawLine(look.peakColor, Offset(px, barTop - barH * 0.5f), Offset(px, barTop + barH * 1.5f), max(3f, barH * 0.25f))
            }
        }
        if (peak != null && look.showPeakValue) {
            val t = tm.measure("MAX " + def.format(peak),
                TextStyle(color = look.peakColor, fontSize = (unit * 0.12f).toSp(), fontFamily = fonts.value))
            val ty = if (angle == 0f) pivot.y - thick / 2 - t.size.height - barH * 0.6f else 0f
            drawText(t, topLeft = Offset(4f, ty.coerceAtLeast(0f)))
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
    peak: Double? = null,
    fonts: GaugeFontSet = GaugeFontSet(FontFamily.Monospace, null),
) {
    val tm = rememberTextMeasurer()
    val warn = isWarn(def, value)
    Canvas(modifier) {
        drawFace(look)
        image?.let {
            drawImageFit(it, Offset(size.width * (0.5f + look.imageOffsetX), size.height * (0.5f + look.imageOffsetY)),
                max(size.width, size.height) * look.imageScale)
        }
        val unit = min(size.height, size.width * 0.6f)
        if (look.showName) {
            val name = tm.measure(def.shortName,
                TextStyle(color = look.nameColor, fontFamily = fonts.label, fontSize = (unit * 0.14f).toSp(), fontWeight = FontWeight.Medium))
            drawText(name, topLeft = Offset(4f + size.width * look.nameOffsetX, size.height * look.nameOffsetY))
        }
        // valuePosition は中心からのずれ (アナログと共通の値を流用。既定 0.42 → 中央やや下)
        val cy = size.height * (0.5f + (look.valuePosition - 0.42f) * 0.5f)
        val cx = size.width * (0.5f + look.valueOffsetX * 0.5f)
        if (look.showValue) {
            // 幅に収まらないときは縮める (メーターの形によってはみ出さないように)
            drawCenteredTextFit(tm, if (unsupported) "非対応" else def.format(value),
                Offset(cx, cy),
                TextStyle(color = if (warn) GaugeColors.Warn else look.valueColor,
                    fontSize = (unit * (if (unsupported) 0.2f else 0.38f) * look.valueSize).toSp(),
                    fontWeight = FontWeight.Bold, fontFamily = fonts.value),
                maxWidth = size.width * 0.96f)
        }
        if (look.showUnit) {
            drawCenteredText(tm, def.unitText,
                Offset(cx + size.width * look.unitOffsetX, cy + unit * 0.3f * max(look.valueSize, 0.6f) + size.height * look.unitOffsetY),
                TextStyle(color = look.unitColor, fontFamily = fonts.label, fontSize = (unit * 0.13f).toSp()))
        }
        if (peak != null) {
            val t = tm.measure("MAX " + def.format(peak),
                TextStyle(color = look.peakColor, fontSize = (unit * 0.12f).toSp(), fontFamily = fonts.value))
            drawText(t, topLeft = Offset(size.width - t.size.width - 4f, 0f))
        }
    }
}


/** バー・デジタル表示の背景色 (角丸の四角) */
internal fun DrawScope.drawFace(look: GaugeLook) {
    val c = look.faceColor ?: return
    val s = look.faceScale
    val r = min(size.width, size.height) * 0.08f * s
    val sz = androidx.compose.ui.geometry.Size(size.width * s, size.height * s)
    drawRoundRect(c, topLeft = Offset((size.width - sz.width) / 2, (size.height - sz.height) / 2), size = sz,
        cornerRadius = CornerRadius(r, r))
}

/**
 * 外周リング。
 * メタル: 実物のメーターのベゼルのような 2 段構成。外側の段は凸面 (上が明るく下が暗い)、
 * 内側の段は凹面 (上が暗く下が明るい) にして、文字盤に向かって一段下がって見えるようにする。
 * 陰影の強さは [GaugeLook.ringDepth]、内側の段の幅は [GaugeLook.ringInner]。
 */
/** 外周リングの幅 (メーターの直径 [d] に対して) */
internal fun ringWidthFor(look: GaugeLook, d: Float): Float = when (look.ring) {
    RingStyle.NONE -> 0f
    RingStyle.LINE -> d * 0.012f
    RingStyle.BOLD -> d * 0.035f
    RingStyle.METAL -> d * 0.05f
} * look.ringThickness

internal fun DrawScope.drawRing(look: GaugeLook, center: Offset, outer: Float, w: Float) {
    val c = look.ringColor
    val depth = look.ringDepth.coerceIn(0f, 1f)
    // 光は真上よりやや左から当たる
    val top = Offset(center.x - outer * 0.25f, center.y - outer)
    val bottom = Offset(center.x + outer * 0.25f, center.y + outer)
    when (look.ring) {
        RingStyle.NONE -> return
        RingStyle.LINE -> { drawCircle(c, outer - w / 2, center, style = Stroke(w)); return }
        RingStyle.BOLD -> {
            drawCircle(c, outer - w / 2, center, style = Stroke(w))
            if (depth > 0f) {
                drawCircle(
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.45f * depth), Color.Transparent, Color.Black.copy(alpha = 0.55f * depth)),
                        start = top, end = bottom,
                    ),
                    outer - w / 2, center, style = Stroke(w),
                )
            }
        }
        RingStyle.METAL -> {
            val innerW = w * look.ringInner.coerceIn(0f, 0.8f)
            val outerW = w - innerW
            // 外側の段 (凸): 上が白っぽく、下に行くほど暗い
            drawCircle(
                Brush.linearGradient(
                    listOf(
                        lerp(c, Color.White, 0.85f * depth),
                        lerp(c, Color.White, 0.35f * depth),
                        c,
                        lerp(c, Color.Black, 0.25f * depth),
                        lerp(c, Color.White, 0.25f * depth),
                    ),
                    start = top, end = bottom,
                ),
                outer - outerW / 2, center, style = Stroke(outerW),
            )
            // 外側の段の外縁の影
            drawCircle(Color.Black.copy(alpha = 0.5f * depth), outer - outerW * 0.03f, center, style = Stroke(max(1f, outerW * 0.06f)))
            if (innerW > 0f) {
                // 内側の段 (凹): 地の色を少し暗くし、上が暗く下が明るい
                val ci = lerp(c, Color.Black, 0.45f)
                drawCircle(
                    Brush.linearGradient(
                        listOf(
                            lerp(ci, Color.Black, 0.5f * depth),
                            ci,
                            lerp(ci, Color.White, 0.35f * depth),
                        ),
                        start = top, end = bottom,
                    ),
                    outer - outerW - innerW / 2, center, style = Stroke(innerW),
                )
                // 段の境目の細い影
                drawCircle(Color.Black.copy(alpha = 0.45f * depth), outer - outerW, center, style = Stroke(max(1f, w * 0.025f)))
            }
        }
    }
    if (depth <= 0f) return
    // 文字盤への落ち影 (リングの内側をうっすら暗く)
    val inner = outer - w
    if (inner > 0f) {
        drawCircle(
            Brush.radialGradient(
                0f to Color.Transparent,
                0.88f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.55f * depth),
                center = center, radius = inner,
            ),
            inner, center,
        )
    }
}

/**
 * 表示用の単位。全角の「℃」「℉」は多くのフォントに無く、標準フォントで代わりに描かれて
 * 見た目がそろわないので、「°C」「°F」(半角の度記号 + 英字) に置き換える
 */
internal val com.obdmeter.core.PidDef.unitText: String
    get() = unit.replace("℃", "°C").replace("℉", "°F")
