package com.obdmeter.ui.gauges

import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.clickable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
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
import kotlin.math.abs
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
        drawFace(look)
        image?.let {
            drawImageFit(it, Offset(size.width * (0.5f + look.imageOffsetX), size.height * (0.5f + look.imageOffsetY)),
                max(size.width, size.height) * look.imageScale)
        }
        val nameH = if (look.showName) size.height * 0.2f else 0f
        val valueH = if (look.showValue) size.height * 0.2f else 0f
        val areaH = size.height - nameH - valueH
        // 外周リングを付けるときは、その分ランプを小さくして内側に収める
        val outer = min(size.width, areaH) * 0.4f
        val ringW = ringWidthFor(look, outer * 2)
        val r = outer - ringW
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
        if (ringW > 0f) drawRing(look, c, outer, ringW)

        val unit = min(size.width, size.height)
        if (look.showName) {
            drawCenteredText(tm, def.shortName,
                Offset(size.width * (0.5f + look.nameOffsetX), nameH / 2 + size.height * look.nameOffsetY),
                TextStyle(color = look.nameColor, fontFamily = fonts.label, fontWeight = FontWeight.Medium,
                    fontSize = (unit * 0.11f * look.labelSize).toSp()))
        }
        if (look.showValue) {
            val text = if (unsupported) "非対応" else def.format(value) + if (look.showUnit) " ${def.unitText}" else ""
            drawCenteredText(tm, text, Offset(size.width / 2, size.height - valueH / 2),
                TextStyle(color = look.valueColor, fontFamily = fonts.value,
                    fontSize = (unit * 0.11f * look.valueSize).toSp()))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 値テーブル
// ---------------------------------------------------------------------------------------------

/**
 * 「項目名 (右寄せ)・値・単位 (左寄せ)」の行を縦に並べる。行数が [GaugeLook.tableMaxRows] を超えたら右に段を増やす。
 * 値の部分には背景色・背景画像を付けられる。
 */
@Composable
internal fun TableGauge(series: List<ChartSeries>, look: GaugeLook, fonts: GaugeFontSet, modifier: Modifier) {
    val image = rememberGaugeImage(look.tableValueImage)
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(modifier) {
        val n = series.size.coerceAtLeast(1)
        val rows = look.tableMaxRows.coerceIn(1, 30).coerceAtMost(n)
        val cols = (n + rows - 1) / rows
        val colW = maxWidth / cols
        val rowH = maxHeight / rows
        // 文字の大きさは行の高さから決める (幅が足りなければ 1 行に収まるよう省略)
        val base = with(density) { (rowH.toPx() * 0.42f).toSp() }
        val inlineName = look.showName && look.tableNameInline
        val nameW = if (look.showName && !inlineName) look.tableNameRatio else 0f
        val nameSize = base * 0.8f * look.labelSize * look.tableNameSize
        val unitSize = base * 0.7f * look.labelSize * look.tableUnitSize
        val inlineUnit = look.showUnit && look.tableUnitInline
        val unitW = if (look.showUnit && !inlineUnit) look.tableUnitRatio else 0f
        val valueW = (1f - nameW - unitW).coerceAtLeast(0.15f)
        Row(Modifier.fillMaxSize()) {
            for (c in 0 until cols) {
                Column(Modifier.width(colW).fillMaxSize()) {
                    for (r in 0 until rows) {
                        val i = c * rows + r
                        val s = series.getOrNull(i)
                        Row(
                            Modifier.height(rowH).fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (s == null) return@Row
                            if (nameW > 0f) {
                                Text(
                                    s.def.shortName,
                                    Modifier.weight(nameW).padding(end = 4.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    color = look.nameColor, fontFamily = fonts.label,
                                    fontSize = nameSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                Modifier
                                    .weight(valueW)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(4.dp))
                                    .then(look.tableValueBg?.let { Modifier.background(it) } ?: Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (image != null) {
                                    androidx.compose.foundation.Image(
                                        image, null, Modifier.matchParentSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                                    )
                                }
                                // 項目名を値の枠の左上に
                                if (inlineName) Text(
                                    s.def.shortName,
                                    Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 1.dp),
                                    color = look.nameColor, fontFamily = fonts.label,
                                    fontSize = nameSize, maxLines = 1, softWrap = false,
                                    lineHeight = nameSize,
                                )
                                if (look.showValue || inlineUnit) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        if (look.showValue) Text(
                                            s.def.format(s.value),
                                            color = if (isWarn(s.def, s.value)) GaugeColors.Warn else look.valueColor,
                                            fontFamily = fonts.value, fontWeight = FontWeight.Bold,
                                            fontSize = base * look.valueSize, maxLines = 1, softWrap = false,
                                        )
                                        // 単位を値のすぐ後ろに付ける
                                        if (inlineUnit && s.def.unitText.isNotEmpty()) Text(
                                            " " + s.def.unitText,
                                            Modifier.padding(bottom = 1.dp),
                                            color = look.unitColor, fontFamily = fonts.label,
                                            fontSize = unitSize, maxLines = 1, softWrap = false,
                                        )
                                    }
                                }
                            }
                            if (unitW > 0f) {
                                Text(
                                    s.def.unitText,
                                    Modifier.weight(unitW).padding(start = 4.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                    color = look.unitColor, fontFamily = fonts.label,
                                    fontSize = unitSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 折れ線グラフ
// ---------------------------------------------------------------------------------------------

/** 目盛りの間隔の候補 (ミリ秒) */
private val TIME_STEPS = listOf(
    60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L,
    3_600_000L, 7_200_000L, 10_800_000L, 21_600_000L, 43_200_000L,
    86_400_000L, 172_800_000L, 604_800_000L, 1_209_600_000L, 2_592_000_000L,
)

/**
 * 複数項目の折れ線グラフ。記録 ([HistoryStore]) を読み、項目ごとに記録のある時刻を線でつなぐ。
 * 縦方向は項目ごとに自分の範囲 (最小〜最大) で合わせて重ねる。上に各項目の現在値。
 * 左右にドラッグで記録の全期間をスクロール、2 本指で 1 画面の期間を拡大縮小、ダブルタップで現在に戻る。
 * [interactive] のときは CSV の書き出し・読み込みボタンも出す。
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
    val keys = series.map { it.def.key }
    val revision by (history?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
    var span by remember(look.chartSpanMs) { mutableLongStateOf(look.chartSpanMs) }
    /** 表示の右端の時刻 (null = 現在に追従) */
    var endAt by remember { mutableStateOf<Long?>(null) }
    /** タップで選んだ時刻 (null = 現在値を表示) */
    var cursorAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    // 記録の一番古い時刻 (ここまでさかのぼれる)
    val earliest by produceState<Long?>(null, keys, revision) { value = history?.earliest(keys) }

    val end = endAt ?: now
    val start = end - span
    // 範囲を丸めて、動かすたびに読み直さないようにする
    val q = (span / 50).coerceAtLeast(1000L)
    val qStart = start / q * q - q
    val qEnd = (end / q + 1) * q
    val data by produceState(emptyMap<String, List<Sample>>(), keys, qStart, qEnd, revision) {
        if (history != null) value = keys.associateWith { k ->
            // 表示範囲の外の直前・直後の点も入れて、端まで線がつながるようにする
            val (before, after) = history.neighbors(k, qStart, qEnd)
            listOfNotNull(before) + history.load(k, qStart, qEnd, 800) + listOfNotNull(after)
        }
    }

    // タップした時刻に一番近い記録の時刻にそろえ、各項目はその時刻に一番近い記録の値を出す
    fun nearest(pts: List<Sample>, t: Long): Sample? = pts.minByOrNull { abs(it.t - t) }
    val cursorTime: Long? = cursorAt?.let { c ->
        data.values.mapNotNull { nearest(it, c) }.minByOrNull { abs(it.t - c) }?.t ?: c
    }
    val cursorValues: Map<String, Double?> = if (cursorTime == null) emptyMap() else
        keys.associateWith { k ->
            // 選んだ時刻から離れすぎた記録は出さない (1 画面の 1/20 まで)
            nearest(data[k].orEmpty(), cursorTime)?.takeIf { abs(it.t - cursorTime) <= max(span / 20, 2_000L) }?.v?.toDouble()
        }

    Column(modifier.padding(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // 各項目の現在値 (時刻を選んでいるときはその時刻の値)
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (cursorTime != null) {
                    Text(
                        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(cursorTime)),
                        color = GaugeColors.Accent, fontSize = (11 * look.labelSize).sp, maxLines = 1,
                        fontWeight = FontWeight.Bold,
                    )
                }
                series.forEachIndexed { i, s ->
                    val shown = if (cursorTime != null) cursorValues[s.def.key] else s.value
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(chartColor(look, i)))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            s.def.shortName + " ",
                            color = look.labelColor, fontFamily = fonts.label,
                            fontSize = (11 * look.labelSize).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            s.def.format(shown) + if (look.showUnit) " ${s.def.unitText}" else "",
                            color = chartColor(look, i), fontFamily = fonts.value, fontWeight = FontWeight.Bold,
                            fontSize = (13 * look.valueSize).sp, maxLines = 1,
                        )
                    }
                }
            }
            if (interactive && history != null) ChartCsvButtons(history, keys)
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (!interactive) Modifier
                    else Modifier
                        .pointerInput(look.chartSpanMs) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val t = System.currentTimeMillis()
                                val oldest = earliest ?: t
                                val maxSpan = max(31 * 86_400_000L, t - oldest)
                                if (zoom != 1f) span = (span / zoom).toLong().coerceIn(60_000L, maxSpan)
                                if (pan.x != 0f && size.width > 0) {
                                    val base = endAt ?: t
                                    // 一番古い記録が左端に来るところまでさかのぼれる
                                    val minEnd = min(t, oldest + span)
                                    val next = (base - (pan.x / size.width * span).toLong()).coerceIn(minEnd, t)
                                    endAt = if (next >= t - span / 100) null else next
                                }
                            }
                        }
                        .pointerInput(look.chartSpanMs) {
                            detectTapGestures(
                                // タップした位置の時刻の値を凡例に出す
                                onTap = { p ->
                                    if (size.width > 0) {
                                        val e = endAt ?: System.currentTimeMillis()
                                        cursorAt = e - span + (p.x / size.width * span).toLong()
                                    }
                                },
                                // ダブルタップで現在に戻り、現在値を出す
                                onDoubleTap = { endAt = null; cursorAt = null; span = look.chartSpanMs },
                            )
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
                val step = TIME_STEPS.firstOrNull { it * 5 >= span } ?: TIME_STEPS.last()
                val fmt = SimpleDateFormat(if (step >= 21_600_000L) "M/d HH:mm" else "HH:mm", Locale.getDefault())
                val tz = java.util.TimeZone.getDefault()
                var t = start - Math.floorMod(start + tz.getOffset(start), step) + step
                while (t <= end) {
                    val x = (t - start).toFloat() / span * w
                    drawLine(GaugeColors.Track.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, plotH), 1f)
                    val lay = tm.measure(fmt.format(Date(t)), labelStyle)
                    val lx = (x - lay.size.width / 2f).coerceIn(0f, max(0f, w - lay.size.width))
                    drawText(lay, topLeft = Offset(lx, plotH + 2f))
                    t += step
                }

                // 項目ごとに、記録のある時刻を線でつなぐ
                var any = false
                series.forEachIndexed { i, s ->
                    val pts = data[s.def.key].orEmpty()
                    if (pts.isEmpty()) return@forEachIndexed
                    any = true
                    val color = chartColor(look, i)
                    val lo = s.def.min
                    val range = (s.def.max - s.def.min).takeIf { it > 0 } ?: 1.0
                    fun x(p: Sample) = (p.t - start).toFloat() / span * w
                    fun y(p: Sample) = plotH - ((p.v - lo) / range).toFloat().coerceIn(-0.02f, 1.02f) * plotH
                    val path = Path()
                    pts.forEachIndexed { idx, p -> if (idx == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p)) }
                    clipRect(0f, 0f, w, plotH) {
                        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        // 点がまばらなときは記録した位置に点を打つ
                        val visible = pts.count { it.t in start..end }
                        if (visible in 1..(w / 8).toInt()) {
                            pts.forEach { p -> if (p.t in start..end) drawCircle(color, 2.5.dp.toPx(), Offset(x(p), y(p))) }
                        }
                    }
                }
                // 選んだ時刻の縦線
                cursorTime?.let { ct ->
                    if (ct in start..end) {
                        val cx = (ct - start).toFloat() / span * w
                        drawLine(GaugeColors.Accent, Offset(cx, 0f), Offset(cx, plotH), 1.5.dp.toPx())
                        series.forEachIndexed { i, s ->
                            val v = cursorValues[s.def.key] ?: return@forEachIndexed
                            val range = (s.def.max - s.def.min).takeIf { it > 0 } ?: 1.0
                            val cy = plotH - ((v - s.def.min) / range).toFloat().coerceIn(-0.02f, 1.02f) * plotH
                            drawCircle(chartColor(look, i), 4.dp.toPx(), Offset(cx, cy))
                            drawCircle(GaugeColors.Text, 4.dp.toPx(), Offset(cx, cy), style = Stroke(1.dp.toPx()))
                        }
                    }
                }
                if (!any) {
                    drawCenteredText(tm, if (history == null) "" else "この期間の記録はありません", Offset(w / 2, plotH / 2), labelStyle)
                }
            }
            if (endAt != null || cursorAt != null) {
                Text(
                    if (endAt != null) "過去を表示中 (ダブルタップで現在へ)" else "時刻を選択中 (ダブルタップで現在値へ)",
                    Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    color = GaugeColors.Text, fontSize = 10.sp,
                )
            }
        }
    }
}

/** グラフの記録の CSV 書き出し・読み込みボタン (アプリの画面でだけ出す) */
@Composable
private fun ChartCsvButtons(history: HistoryStore, keys: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching {
                val n = context.contentResolver.openOutputStream(uri)?.use { history.exportCsv(keys, it) }
                    ?: error("ファイルを開けませんでした")
                "$n 件を書き出しました"
            }.getOrElse { "書き出しに失敗しました: ${it.message}" }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching {
                val (ok, bad) = context.contentResolver.openInputStream(uri)?.use { history.importCsv(it) }
                    ?: error("ファイルを開けませんでした")
                "$ok 件を読み込みました" + if (bad > 0) " (読めない行 $bad 件)" else ""
            }.getOrElse { "読み込みに失敗しました: ${it.message}" }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    Row {
        CsvButton("書出") {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val name = "obdmeter_chart_$stamp.csv"
            // 保存先を選ぶ画面が無い端末 (車載ナビなど) では、ダウンロードフォルダに直接保存する
            val launched = runCatching { exporter.launch(name) }.isSuccess
            if (!launched) {
                scope.launch {
                    val msg = runCatching {
                        val (n, where) = exportToDownloads(context, history, keys, name)
                        "$n 件を $where に保存しました"
                    }.getOrElse { "書き出しに失敗しました: ${it.message}" }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        CsvButton("読込") {
            val ok = runCatching { importer.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) }.isSuccess
            if (!ok) Toast.makeText(context, "この端末ではファイルを選ぶ画面を開けません", Toast.LENGTH_LONG).show()
        }
    }
}

/** ダウンロードフォルダ (Download/OBDMeter) に CSV を直接書き出す。件数と保存先の説明を返す */
private suspend fun exportToDownloads(
    context: android.content.Context,
    history: HistoryStore,
    keys: List<String>,
    name: String,
): Pair<Int, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/OBDMeter")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("保存先を作れませんでした")
        val n = context.contentResolver.openOutputStream(uri)?.use { history.exportCsv(keys, it) } ?: error("ファイルを開けませんでした")
        n to "Download/OBDMeter/$name"
    } else {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val f = java.io.File(dir, name)
        val n = f.outputStream().use { history.exportCsv(keys, it) }
        n to f.absolutePath
    }
}

@Composable
private fun CsvButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(GaugeColors.Card.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { Text(text, fontSize = 11.sp, color = GaugeColors.Text) }
}
