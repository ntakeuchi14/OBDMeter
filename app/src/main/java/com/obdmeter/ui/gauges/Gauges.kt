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
import androidx.compose.ui.graphics.StrokeJoin
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
    val barArc: Boolean = false,
    val barTaper: Float = 0f,
    val barOffColor: Color? = null,
    val barZoneColors: Boolean = false,
    val barMidColor: Color = Color(0xFFFFC107),
    val barMidFrom: Double? = null,
    val glow: Float = 0f,
    val smoothing: Float = 0.5f,
    val showTicks: Boolean = true,
    val arcRoundCap: Boolean = false,
    val trackColor: Color? = null,
    val arcTipDot: Boolean = false,
    val arcTipColor: Color = Color.White,
    val ghostDigits: Boolean = false,
    val ghostAlpha: Float = 0.15f,
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
        val v = rememberDisplayValue(def, if (unsupported) null else value, look.smoothing)
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


/**
 * 生の値をそのまま描くと、アイドリングの ±数 rpm の揺れで数字と針が震えて見える。
 * 指数移動平均でならし、[DISPLAY_INTERVAL_MS] ごとに表示用の値を更新し、
 * さらに [PidDef.displayStep] 単位 (回転数なら 10rpm) に丸める。
 */
@Composable
private fun rememberDisplayValue(def: PidDef, value: Double?, smoothing: Float = 0.5f): Double? {
    val latest by rememberUpdatedState(value)
    var shown by remember(def.key) { mutableStateOf(value) }
    LaunchedEffect(def.key, smoothing) {
        var ema = Double.NaN
        // なめらかさ: 0 → ならさず短い間隔で更新、0.5 → 従来 (半分ずつ近づける・200ms)、1 → ゆっくり
        val alpha = smoothingAlpha(smoothing)
        val interval = displayIntervalMs(smoothing)
        while (true) {
            val x = latest
            if (x == null) {
                ema = Double.NaN
                shown = null
            } else {
                ema = if (ema.isNaN()) x else ema + (x - ema) * alpha
                val step = def.displayStep
                shown = if (step > 0) Math.round(ema / step) * step else ema
            }
            delay(interval)
        }
    }
    return shown
}



@Composable
private fun animated(def: PidDef, value: Double?, smoothing: Float = 0.5f): Float {
    // 値が一瞬途切れても針を 0 に戻さず、最後の位置で止めておく (ちらつき防止)
    val last = remember { floatArrayOf(0f) }
    val target = value?.let { fraction(def, it) }?.also { last[0] = it } ?: last[0]
    // 表示値の更新間隔と同じ長さで等速に動かし、次の値へ途切れなくつなぐ
    return animateFloatAsState(target, tween(displayIntervalMs(smoothing).toInt(), easing = LinearEasing), label = "gauge").value
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
    val f = animated(def, value, look.smoothing)
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
        // 端を丸くするときは、丸みの分だけ角度を内側に詰めて全体の範囲を変えない
        val capInset = if (look.arcRoundCap && arcR > 0f) Math.toDegrees((stroke / 2 / arcR).toDouble()).toFloat()
            .coerceAtMost(look.sweep / 4) else 0f
        val start = look.startAngle + capInset
        val sweep = look.sweep - capInset * 2
        val cap = if (look.arcRoundCap) StrokeCap.Round else StrokeCap.Butt

        if (look.showTrack && stroke > 0f) {
            drawArc(look.trackColor ?: GaugeColors.Track, start, sweep, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = cap))
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
            val arcColor = if (warn) GaugeColors.Warn else look.accent
            drawArc(arcColor, start, sweep * f, false,
                arcTopLeft, arcSize, style = Stroke(stroke, cap = cap))
            // 弧の先の丸い点
            if (look.arcTipDot) {
                val ta = Math.toRadians((start + sweep * f).toDouble())
                val tp = Offset(center.x + arcR * cos(ta).toFloat(), center.y + arcR * sin(ta).toFloat())
                drawCircle(look.arcTipColor, stroke * 0.32f, tp)
            }
        }

        // 目盛り (主・小) と数字
        val scale = TickScale.of(def.min, def.max, look.majorDivisions)
        val labelSize = (d * 0.07f * look.labelSize).toSp()
        val majors = scale.values(def.max)
        for ((i, t) in majors.withIndex()) {
            val a = Math.toRadians((start + sweep * fraction(def, t)).toDouble())
            if (look.showTicks) drawLine(look.tickColor,
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
            if (look.showTicks && look.minorDivisions > 0 && i < majors.lastIndex) {
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
    val f = animated(def, value, look.smoothing)
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
            val vColor = if (warn) GaugeColors.Warn else look.valueColor
            val valueStyle = TextStyle(
                color = vColor,
                fontSize = (unit * (if (unsupported) 0.2f else 0.34f) * look.valueSize).toSp(),
                fontWeight = FontWeight.Bold, fontFamily = fonts.value,
                shadow = glowShadow(vColor, look.glow, unit * 0.34f * look.valueSize),
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
            val valueRight = w - unitLayout.size.width - 10f + dx
            if (look.ghostDigits && !unsupported) {
                // 消えている 7 セグ (8) を後ろにうっすら
                val ghost = tm.measure(ghostText(def), valueStyle.copy(color = look.valueColor.copy(alpha = look.ghostAlpha), shadow = null))
                drawText(ghost, topLeft = Offset(valueRight - ghost.size.width, valueY))
            }
            drawText(valueLayout, topLeft = Offset(valueRight - valueLayout.size.width, valueY))
        }

        drawBar(def, value, f, warn, look, peak, tm, fonts, unit)
    }
}


/** 発光のにじみ (文字の影) */
internal fun glowShadow(color: Color, glow: Float, size: Float): androidx.compose.ui.graphics.Shadow? =
    if (glow <= 0f) null else androidx.compose.ui.graphics.Shadow(color.copy(alpha = 0.9f), Offset.Zero, size * 0.12f * glow + 2f)

/**
 * バー本体。直線 (角度付き) か弧、実線か破線 (マス)、先細り、範囲ごとの色分け、発光に対応する。
 * 位置 p (0〜1) ごとに太さ・色が決まり、[p0, p1] の帯を 1 つの Path として塗る。
 */
private fun DrawScope.drawBar(
    def: PidDef, value: Double?, f: Float, warn: Boolean, look: GaugeLook, peak: Double?,
    tm: TextMeasurer, fonts: GaugeFontSet, unit: Float,
) {
    val w = size.width
    val h = size.height
    val barHidden = !look.showValueArc || look.arcWidth <= 0f
    val arc = look.barArc
    val baseThick = if (look.arcWidth <= 0f) 0f else
        if (arc) max(min(w, h) * 0.14f * look.arcWidth, 4f) else max(h * 0.12f * look.arcWidth, 4f)
    // バーが無いときのレッドゾーンは細い帯で描く
    val zoneH = if (barHidden) max(h * 0.04f, 3f) else baseThick
    val thick = max(baseThick, zoneH)
    val taper = look.taperOrZero()
    fun th(p: Float) = baseThick * (1f - taper * (1f - p))
    val segs = look.barSegments
    val gapFrac = if (segs > 0) look.barSegmentGap / segs else 0f

    // ---- 形ごとの帯の Path ----
    val bandPath: (Float, Float, Float?) -> Path
    var pointAt: (Float, Float) -> Offset   // (位置, 内側からの距離) → 座標
    var lineLen = w
    if (!arc) {
        val angle = look.barAngle
        val rad = Math.toRadians(angle.toDouble())
        val pivot = if (angle == 0f) Offset(w / 2, h - thick / 2 - h * 0.08f) else Offset(w / 2, h / 2)
        lineLen = if (angle == 0f) w else {
            val c = abs(cos(rad)).toFloat()
            val sn = abs(sin(rad)).toFloat()
            val byW = if (c > 1e-3f) (w - thick * sn) / c else Float.MAX_VALUE
            val byH = if (sn > 1e-3f) (h - thick * c) / sn else Float.MAX_VALUE
            min(byW, byH) * 0.94f
        }.coerceAtLeast(4f)
        val ux = cos(rad).toFloat(); val uy = sin(rad).toFloat()
        // 帯の「下側」(太さの基準) は進行方向の右手 (横向きなら下)
        val nx = -uy; val ny = ux
        val origin = Offset(pivot.x - ux * lineLen / 2 + nx * thick / 2, pivot.y - uy * lineLen / 2 + ny * thick / 2)
        pointAt = { p, d -> Offset(origin.x + ux * lineLen * p - nx * d, origin.y + uy * lineLen * p - ny * d) }
        bandPath = { p0, p1, fixed ->
            Path().apply {
                val t0 = fixed ?: th(p0); val t1 = fixed ?: th(p1)
                val off0 = (thick - (fixed ?: baseThick)) / 2
                val o0 = if (taper > 0f && fixed == null) 0f else off0
                moveTo(pointAt(p0, o0).x, pointAt(p0, o0).y)
                lineTo(pointAt(p1, o0).x, pointAt(p1, o0).y)
                lineTo(pointAt(p1, o0 + t1).x, pointAt(p1, o0 + t1).y)
                lineTo(pointAt(p0, o0 + t0).x, pointAt(p0, o0 + t0).y)
                close()
            }
        }
    } else {
        // 弧: 開始〜終了角度の範囲が枠いっぱいに収まる中心と半径を求める
        val start = look.startAngle
        val sweep = look.sweep
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var k = 0
        while (k <= 90) {
            val a = Math.toRadians((start + sweep * k / 90f).toDouble())
            val x = cos(a).toFloat(); val y = sin(a).toFloat()
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            k++
        }
        val bw = (maxX - minX).coerceAtLeast(0.2f)
        val bh = (maxY - minY).coerceAtLeast(0.2f)
        val margin = thick * 0.6f + 2f
        val r = min((w - margin * 2) / bw, (h - margin * 2) / bh).coerceAtLeast(8f)
        val cx = w / 2 - (minX + maxX) / 2 * r
        val cy = h / 2 - (minY + maxY) / 2 * r
        val rIn = r - thick / 2
        pointAt = { p, d ->
            val a = Math.toRadians((start + sweep * p).toDouble())
            Offset(cx + cos(a).toFloat() * (rIn + d), cy + sin(a).toFloat() * (rIn + d))
        }
        bandPath = { p0, p1, fixed ->
            Path().apply {
                val n = max(2, ((p1 - p0) * sweep / 3f).toInt() + 1)
                val o0 = if (taper > 0f && fixed == null) 0f else (thick - (fixed ?: baseThick)) / 2
                for (i in 0..n) {
                    val p = p0 + (p1 - p0) * i / n
                    val pt = pointAt(p, o0)
                    if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                }
                for (i in n downTo 0) {
                    val p = p0 + (p1 - p0) * i / n
                    val pt = pointAt(p, o0 + (fixed ?: th(p)))
                    lineTo(pt.x, pt.y)
                }
                close()
            }
        }
    }

    // ---- 色 ----
    val litBase = if (warn && !look.barZoneColors) GaugeColors.Warn else look.accent
    fun zoneColor(p: Float): Color {
        if (!look.barZoneColors) return litBase
        val v = def.min + (def.max - def.min) * p
        val z = look.redZone
        if (z != null && v >= z.start && v <= z.endInclusive) return look.redZoneColor
        val m = look.barMidFrom
        if (m != null && v >= m) return look.barMidColor
        return look.accent
    }
    fun offColor(p: Float): Color = look.barOffColor ?: if (look.barZoneColors || look.glow > 0f) zoneColor(p).copy(alpha = 0.13f) else GaugeColors.Track
    fun lit(path: Path, color: Color) {
        if (look.glow > 0f) {
            val gw = baseThick * 0.5f * look.glow + 2f
            drawPath(path, color.copy(alpha = 0.18f * look.glow), style = Stroke(gw * 2, join = StrokeJoin.Round))
            drawPath(path, color.copy(alpha = 0.30f * look.glow), style = Stroke(gw, join = StrokeJoin.Round))
        }
        drawPath(path, color)
    }
    /** [lo, hi] を塗る (破線ならマスごと)。[colorAt] は位置ごとの色 */
    fun fill(lo0: Float, hi0: Float, colorAt: (Float) -> Color, glowOn: Boolean, fixed: Float? = null) {
        val lo = min(lo0, hi0); val hi = max(lo0, hi0)
        if (segs > 0) {
            for (i in 0 until segs) {
                val mid = (i + 0.5f) / segs
                if (mid < lo || mid > hi) continue
                val path = bandPath(i.toFloat() / segs + gapFrac / 2, (i + 1f) / segs - gapFrac / 2, fixed)
                if (glowOn) lit(path, colorAt(mid)) else drawPath(path, colorAt(mid))
            }
        } else if (hi > lo) {
            // 実線: 色の切れ目ごとに分けて塗る
            val cuts = mutableListOf(lo, hi)
            if (look.barZoneColors) {
                look.barMidFrom?.let { cuts += fraction(def, it) }
                look.redZone?.let { cuts += fraction(def, it.start); cuts += fraction(def, it.endInclusive) }
            }
            val pts = cuts.filter { it in lo..hi }.distinct().sorted()
            for (j in 0 until pts.size - 1) {
                if (pts[j + 1] - pts[j] <= 1e-4f) continue
                val path = bandPath(pts[j], pts[j + 1], fixed)
                val c = colorAt((pts[j] + pts[j + 1]) / 2)
                if (glowOn) lit(path, c) else drawPath(path, c)
            }
        }
    }

    // 横の実線 (先細りなし) は今までどおり角丸で描く
    val simpleLine = !arc && segs <= 0 && taper <= 0f
    if (simpleLine) {
        val angle = look.barAngle
        val pivot = if (angle == 0f) Offset(w / 2, h - thick / 2 - h * 0.08f) else Offset(w / 2, h / 2)
        val len = lineLen
        val r = CornerRadius(baseThick / 2, baseThick / 2)
        withTransform({
            translate(pivot.x - len / 2, pivot.y - thick / 2)
            rotate(angle, Offset(len / 2, thick / 2))
        }) {
            val top = (thick - baseThick) / 2
            if (look.showTrack && baseThick > 0f) drawRoundRect(look.barOffColor ?: GaugeColors.Track, Offset(0f, top), Size(len, baseThick), r)
            look.redZone?.let { z ->
                val s0 = fraction(def, z.start); val e0 = fraction(def, z.endInclusive)
                if (e0 > s0 && !look.barZoneColors) drawRect(look.redZoneColor, Offset(len * s0, (thick - zoneH) / 2), Size(len * (e0 - s0), zoneH))
            }
            if (value != null && !barHidden) {
                if (def.min < 0 && def.max > 0) {
                    val zero = fraction(def, 0.0)
                    val x0 = len * min(zero, f)
                    drawRoundRect(litBase, Offset(x0, top), Size((len * abs(f - zero)).coerceAtLeast(3f), baseThick), r)
                    drawLine(look.tickColor, Offset(len * zero, top - 4f), Offset(len * zero, top + baseThick + 4f), 2f)
                } else if (look.barZoneColors) {
                    // 色分けは帯ごと
                } else {
                    if (look.glow > 0f) {
                        drawRoundRect(litBase.copy(alpha = 0.25f * look.glow), Offset(-4f, top - 4f), Size(len * f + 8f, baseThick + 8f),
                            CornerRadius(baseThick / 2 + 4f, baseThick / 2 + 4f))
                    }
                    drawRoundRect(litBase, Offset(0f, top), Size(len * f, baseThick), r)
                }
            }
        }
        if (value != null && !barHidden && look.barZoneColors && !(def.min < 0 && def.max > 0)) fill(0f, f, ::zoneColor, true)
    } else {
        if (look.showTrack && baseThick > 0f) fill(0f, 1f, ::offColor, false)
        // レッドゾーンの帯 (色分けするときはマスの色で表すので描かない)
        if (!look.barZoneColors) look.redZone?.let { z ->
            val s0 = fraction(def, z.start); val e0 = fraction(def, z.endInclusive)
            if (e0 > s0) {
                if (barHidden) fill(s0, e0, { look.redZoneColor }, false, fixed = zoneH)
                else if (segs <= 0) fill(s0, e0, { look.redZoneColor.copy(alpha = 0.55f) }, false)
                else fill(s0, e0, { look.redZoneColor.copy(alpha = 0.35f) }, false)
            }
        }
        if (value != null && !barHidden) {
            if (def.min < 0 && def.max > 0) {
                val zero = fraction(def, 0.0)
                fill(zero, f, ::zoneColor, true)
                val a = pointAt(zero, -4f); val b = pointAt(zero, thick + 4f)
                drawLine(look.tickColor, a, b, 2f)
            } else {
                fill(0f, f, ::zoneColor, true)
            }
        }
    }
    if (peak != null) {
        val pp = fraction(def, peak)
        val a = pointAt(pp, if (simpleLine) -baseThick * 0.5f + (thick - baseThick) / 2 else -baseThick * 0.3f)
        val b = pointAt(pp, if (simpleLine) baseThick * 1.5f + (thick - baseThick) / 2 else thick + baseThick * 0.3f)
        drawLine(look.peakColor, a, b, max(3f, baseThick * 0.25f))
        if (look.showPeakValue) {
            val t = tm.measure("MAX " + def.format(peak),
                TextStyle(color = look.peakColor, fontSize = (unit * 0.12f).toSp(), fontFamily = fonts.value))
            drawText(t, topLeft = Offset(4f, 0f))
        }
    }
}

private fun GaugeLook.taperOrZero() = barTaper.coerceIn(0f, 1f)

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
            val vColor = if (warn) GaugeColors.Warn else look.valueColor
            val style = TextStyle(color = vColor,
                fontSize = (unit * (if (unsupported) 0.2f else 0.38f) * look.valueSize).toSp(),
                fontWeight = FontWeight.Bold, fontFamily = fonts.value,
                shadow = glowShadow(vColor, look.glow, unit * 0.38f * look.valueSize))
            val text = if (unsupported) "非対応" else def.format(value)
            if (look.ghostDigits && !unsupported) {
                // 消えている 7 セグ (8) を中央に置き、数値はその右端にそろえる
                val gs = style.copy(color = look.valueColor.copy(alpha = look.ghostAlpha), shadow = null)
                var ghost = tm.measure(ghostText(def), gs)
                var vs = style
                val maxW = size.width * 0.96f
                if (ghost.size.width > maxW && maxW > 0f) {
                    val k = maxW / ghost.size.width
                    ghost = tm.measure(ghostText(def), gs.copy(fontSize = gs.fontSize * k))
                    vs = style.copy(fontSize = style.fontSize * k)
                }
                val gx = cx - ghost.size.width / 2f
                val gy = cy - ghost.size.height / 2f
                drawText(ghost, topLeft = Offset(gx, gy))
                val vl = tm.measure(text, vs)
                drawText(vl, topLeft = Offset(gx + ghost.size.width - vl.size.width, gy))
            } else {
                // 幅に収まらないときは縮める (メーターの形によってはみ出さないように)
                drawCenteredTextFit(tm, text, Offset(cx, cy), style, maxWidth = size.width * 0.96f)
            }
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
