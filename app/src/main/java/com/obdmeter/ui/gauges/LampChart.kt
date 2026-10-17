package com.obdmeter.ui.gauges

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obdmeter.core.PidDef
import com.obdmeter.data.HistoryStore
import com.obdmeter.data.Sample
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** 折れ線グラフの記録 (アプリ全体で 1 つ)。画面のルートとフロート表示で渡す */
val LocalHistory = staticCompositionLocalOf<HistoryStore?> { null }

/** 折れ線グラフの 1 項目 */
class ChartSeries(val def: PidDef, val value: Double?)

/** 線の色の自動割り当て */
val CHART_PALETTE = listOf(
    Color(0xFF4FC3F7), Color(0xFFFF9800), Color(0xFF66BB6A),
    Color(0xFFEC407A), Color(0xFFFFCA28), Color(0xFFAB47BC),
)

fun chartColor(look: GaugeLook, index: Int): Color =
    look.chartColors.getOrNull(index) ?: if (index == 0) look.accent else CHART_PALETTE[index % CHART_PALETTE.size]

// ---------------------------------------------------------------------------------------------
// 2 値ランプ
// ---------------------------------------------------------------------------------------------

/** 条件式を満たすと点灯するランプ。条件が空なら警告値を超えたときに点灯する */
@Composable
internal fun LampGauge(
    def: PidDef,
    value: Double?,
    unsupported: Boolean,
    look: GaugeLook,
    image: ImageBitmap?,
    modifier: Modifier,
    fonts: GaugeFontSet,
) {
    val tm = rememberTextMeasurer()
    val on = !unsupported && value != null && (look.lampCondition?.test(value) ?: isWarn(def, value))
    Canvas(modifier) {
        if (image == null) drawFace(look)
        image?.let {
            drawImageFit(it, Offset(size.width * (0.5f + look.imageOffsetX), size.height * (0.5f + look.imageOffsetY)),
                max(size.width, size.height) * look.imageScale)
        }
        val nameH = if (look.showName) size.height * 0.2f else 0f
        val valueH = if (look.showValue) size.height * 0.2f else 0f
        val areaH = size.height - nameH - valueH
        val r = min(size.width, areaH) * 0.4f
        val c = Offset(size.width / 2, nameH + areaH / 2)
        val color = if (on) look.lampOnColor else look.lampOffColor
        if (on) {
            // 光のにじみ
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.6f), color.copy(alpha = 0f)), center = c, radius = r * 1.7f),
                r * 1.7f, c,
            )
        }
        // 玉の立体感 (左上が明るい)
        drawCircle(
            Brush.radialGradient(
                listOf(lerp(color, Color.White, if (on) 0.6f else 0.2f), color, lerp(color, Color.Black, 0.4f)),
                center = Offset(c.x - r * 0.35f, c.y - r * 0.35f), radius = r * 1.5f,
            ),
            r, c,
        )
        drawCircle(Color.Black.copy(alpha = 0.55f), r, c, style = Stroke(max(1f, r * 0.08f)))

        val unit = min(size.width, size.height)
        if (look.showName) {
            drawCenteredText(tm, def.shortName,
                Offset(size.width * (0.5f + look.nameOffsetX), nameH / 2 + size.height * look.nameOffsetY),
                TextStyle(color = look.nameColor, fontFamily = fonts.label, fontWeight = FontWeight.Medium,
                    fontSize = (unit * 0.11f * look.labelSize).toSp()))
        }
        if (look.showValue) {
            val text = if (unsupported) "非対応" else def.format(value) + if (look.showUnit) " ${def.unit}" else ""
            drawCenteredText(tm, text, Offset(size.width / 2, size.height - valueH / 2),
                TextStyle(color = look.valueColor, fontFamily = fonts.value,
                    fontSize = (unit * 0.11f * look.valueSize).toSp()))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 折れ線グラフ
// ---------------------------------------------------------------------------------------------

/** 目盛りの間隔の候補 (ミリ秒) */
private val TIME_STEPS = listOf(
    1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L,
    60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L,
    3_600_000L, 7_200_000L, 10_800_000L, 21_600_000L, 43_200_000L, 86_400_000L,
)

/**
 * 複数項目の折れ線グラフ。項目ごとに自分の範囲 (最小〜最大) で縦方向を合わせて重ねて描く。
 * 上に各項目の現在値。左右にドラッグで過去へスクロール、2 本指で時間の幅を拡大縮小、
 * ダブルタップで現在の表示に戻る。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartGauge(
    series: List<ChartSeries>,
    look: GaugeLook,
    fonts: GaugeFontSet,
    interactive: Boolean,
    modifier: Modifier,
) {
    val history = LocalHistory.current
    val tm = rememberTextMeasurer()
    val maxBack = look.historyDays * 86_400_000L
    var span by remember(look.chartSpanMs) { mutableLongStateOf(look.chartSpanMs) }
    /** 表示の右端の時刻 (null = 現在に追従) */
    var endAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(if (endAt == null) 250 else 1000)
        }
    }

    val end = endAt ?: now
    val start = end - span
    val keys = series.map { it.def.key }

    // メモリに無い範囲はデータベースから読む (範囲を丸めて、動かすたびに読み直さないようにする)
    val needDb = history != null && keys.any { start < history.memoryStart(it) }
    val q = (span / 40).coerceAtLeast(1_000L)
    val qStart = start / q * q - q
    val qEnd = (end / q + 1) * q
    val fromDb by produceState(emptyMap<String, List<Sample>>(), keys, qStart, qEnd, needDb) {
        if (needDb && history != null) value = keys.associateWith { history.load(it, qStart, qEnd, 800) }
    }

    Column(modifier.padding(4.dp)) {
        // 各項目の現在値
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(chartColor(look, i)))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        s.def.shortName + " ",
                        color = look.labelColor, fontFamily = fonts.label,
                        fontSize = (11 * look.labelSize).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        s.def.format(s.value) + if (look.showUnit) " ${s.def.unit}" else "",
                        color = chartColor(look, i), fontFamily = fonts.value, fontWeight = FontWeight.Bold,
                        fontSize = (13 * look.valueSize).sp, maxLines = 1,
                    )
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (!interactive) Modifier
                    else Modifier
                        .pointerInput(look.chartSpanMs, maxBack) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val t = System.currentTimeMillis()
                                if (zoom != 1f) span = (span / zoom).toLong().coerceIn(5_000L, maxBack)
                                if (pan.x != 0f && size.width > 0) {
                                    val base = endAt ?: t
                                    val next = (base - (pan.x / size.width * span).toLong())
                                        .coerceIn(t - maxBack + span, t)
                                    endAt = if (next >= t - 500) null else next
                                }
                            }
                        }
                        .pointerInput(look.chartSpanMs) {
                            detectTapGestures(onDoubleTap = { endAt = null; span = look.chartSpanMs })
                        }
                )
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val labelStyle = TextStyle(color = GaugeColors.SubText, fontSize = 10.sp)
                val labelH = tm.measure("00:00", labelStyle).size.height.toFloat()
                val plotH = size.height - labelH - 2f
                val w = size.width
                if (plotH <= 4f || w <= 4f) return@Canvas

                // 横の補助線
                for (i in 0..4) {
                    val y = plotH * i / 4f
                    drawLine(GaugeColors.Track.copy(alpha = if (i == 0 || i == 4) 0.9f else 0.5f), Offset(0f, y), Offset(w, y), 1f)
                }
                // 時刻の目盛り
                val step = TIME_STEPS.firstOrNull { it * 6 >= span } ?: TIME_STEPS.last()
                val fmt = SimpleDateFormat(
                    when {
                        step >= 21_600_000L -> "M/d HH:mm"
                        step >= 60_000L -> "HH:mm"
                        else -> "HH:mm:ss"
                    },
                    Locale.getDefault(),
                )
                val tz = java.util.TimeZone.getDefault()
                var t = start - Math.floorMod(start + tz.getOffset(start), step) + step
                while (t <= end) {
                    val x = (t - start).toFloat() / span * w
                    drawLine(GaugeColors.Track.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, plotH), 1f)
                    val lay = tm.measure(fmt.format(Date(t)), labelStyle)
                    val lx = (x - lay.size.width / 2f).coerceIn(0f, w - lay.size.width)
                    drawText(lay, topLeft = Offset(lx, plotH + 2f))
                    t += step
                }

                // 線
                val gap = max(30_000L, span / 20)
                var any = false
                series.forEachIndexed { i, s ->
                    val k = s.def.key
                    val mem = history?.recent(k, start - q) ?: emptyList()
                    val memStart = mem.firstOrNull()?.t ?: Long.MAX_VALUE
                    val pts = (fromDb[k].orEmpty().filter { it.t < memStart } + mem)
                        .filter { it.t in (start - q)..(end + q) }
                    if (pts.size < 2) return@forEachIndexed
                    any = true
                    val lo = s.def.min
                    val range = (s.def.max - s.def.min).takeIf { it > 0 } ?: 1.0
                    val path = Path()
                    var prevT = Long.MIN_VALUE
                    // 点が多すぎるときは間引く (画面の幅の 2 倍程度)
                    val stride = max(1, pts.size / (w.toInt() * 2).coerceAtLeast(1))
                    var idx = 0
                    while (idx < pts.size) {
                        val p = pts[idx]
                        val x = (p.t - start).toFloat() / span * w
                        val norm = ((p.v - lo) / range).toFloat().coerceIn(-0.02f, 1.02f)
                        val y = plotH - norm * plotH
                        if (prevT == Long.MIN_VALUE || p.t - prevT > gap) path.moveTo(x, y) else path.lineTo(x, y)
                        prevT = p.t
                        idx += stride
                    }
                    drawPath(path, chartColor(look, i), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (!any) {
                    drawCenteredText(tm, if (history == null) "" else "記録がありません", Offset(w / 2, plotH / 2), labelStyle)
                }
            }
            if (endAt != null) {
                Text(
                    "過去を表示中 (ダブルタップで現在へ)",
                    Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    color = GaugeColors.Text, fontSize = 10.sp,
                )
            }
        }
    }
}
