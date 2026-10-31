package com.obdmeter.ui.gauges

import com.obdmeter.util.format

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 折れ線グラフの記録 (アプリ全体で 1 つ)。画面のルートとフロート表示で渡す */
val LocalHistory = staticCompositionLocalOf<HistoryStore?> { null }



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
                    fontSize = (unit * 0.11f * look.labelSize * look.tableNameSize).toSp()))
        }
        if (look.showValue) {
            val text = if (unsupported) "非対応" else look.formatValue(def, value) + if (look.showUnit) " ${def.unitText}" else ""
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
        // 値と値の間 (行・段の間だけ。外側には付けない)
        val rowGap = look.tableRowGap.dp
        val colGap = look.tableColGap.dp
        val colW = ((maxWidth - colGap * (cols - 1)) / cols).coerceIn(1.dp, maxWidth)
        val rowH = ((maxHeight - rowGap * (rows - 1)) / rows).coerceIn(1.dp, maxHeight)
        // 文字の大きさは行の高さから決める (幅が足りなければ 1 行に収まるよう省略)
        val base = with(density) { (rowH.toPx() * (1f - look.tableMarginBottom) * 0.42f).toSp() }
        val inlineName = look.showName && look.tableNameInline
        val nameW = if (look.showName && !inlineName) look.tableNameRatio else 0f
        val nameSize = base * 0.8f * look.labelSize * look.tableNameSize
        val unitSize = base * 0.7f * look.labelSize * look.tableUnitSize
        val inlineUnit = look.showUnit && look.tableUnitInline
        val unitW = if (look.showUnit && !inlineUnit) look.tableUnitRatio else 0f
        val valueW = (1f - nameW - unitW).coerceAtLeast(0.15f)
        // 行・段の位置を直接決めて置く (間をマイナスにすると、文字の上下の空きを重ねて詰められる)
        Box(Modifier.fillMaxSize()) {
            for (c in 0 until cols) {
                    for (r in 0 until rows) {
                        val i = c * rows + r
                        val s = series.getOrNull(i)
                        Row(
                            Modifier
                                .offset((colW + colGap) * c, (rowH + rowGap) * r)
                                .size(colW, rowH)
                                .padding(start = 2.dp)
                                // 下と右の余白 (行の高さ・段の幅に対する比率)
                                .padding(end = 2.dp + colW * look.tableMarginRight, bottom = rowH * look.tableMarginBottom),
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
                                    .then(look.tableValueBg?.let { Modifier.background(it) } ?: Modifier)
                                    // 値の部分の立体感 (背景色か背景画像があるときだけ)
                                    .then(
                                        if (look.tableValueBg == null && look.tableValueImage == null) Modifier
                                        else Modifier.drawWithContent {
                                            drawContent()
                                            val r = 4.dp.toPx()
                                            val rect = androidx.compose.ui.geometry.Rect(Offset.Zero, size)
                                            drawPanel(
                                                androidx.compose.ui.graphics.Path().apply {
                                                    addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, androidx.compose.ui.geometry.CornerRadius(r, r)))
                                                },
                                                rect, look.panelStyle, look.panelDepth, look.panelGloss,
                                            )
                                        }
                                    ),
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
                                        if (look.showValue) {
                                            val vColor = if (isWarn(s.def, s.value)) GaugeColors.Warn else look.valueColor
                                            // 消えている 7 セグを後ろに置き、右端をそろえる
                                            Box(contentAlignment = Alignment.CenterEnd) {
                                                if (look.ghostDigits) Text(
                                                    ghostText(s.def),
                                                    color = look.valueColor.copy(alpha = look.ghostAlpha),
                                                    fontFamily = fonts.value, fontWeight = FontWeight.Bold,
                                                    fontSize = base * look.valueSize, maxLines = 1, softWrap = false,
                                                )
                                                Text(
                                                    s.def.format(s.value),
                                                    color = vColor,
                                                    fontFamily = fonts.value, fontWeight = FontWeight.Bold,
                                                    fontSize = base * look.valueSize, maxLines = 1, softWrap = false,
                                                    style = androidx.compose.ui.text.TextStyle(
                                                        shadow = glowShadow(vColor, look.glow, with(density) { (base * look.valueSize).toPx() }),
                                                    ),
                                                )
                                            }
                                        }
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

// ---------------------------------------------------------------------------------------------
// 折れ線グラフ
// ---------------------------------------------------------------------------------------------


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
    /** このグラフの記録の置き場所 ([com.obdmeter.data.HistorySlot])。null = 記録を出さない */
    slot: String?,
) {
    val history = LocalHistory.current?.takeIf { slot != null }
    val g = slot.orEmpty()
    val tm = rememberTextMeasurer()
    // 現在値が変わるたびにグラフ全体を描き直さないよう、描画に使うものは中身が同じ間は同じものを使う
    @Suppress("NAME_SHADOWING") val look = remember(look) { look }
    val plotSeries = remember(series.map { it.def to it.right }) { series.map { ChartSeries(it.def, null, it.right) } }
    val keys = remember(plotSeries) { plotSeries.map { it.def.key } }
    var span by remember(look.chartSpanMs) { mutableLongStateOf(look.chartSpanMs) }
    // 記録の読み直しは、記録が増えても一定の間隔まで (グラフが多いときに読み直しが続かないように)
    var revision by remember { mutableLongStateOf(0L) }
    val refreshMs = (span / 300).coerceIn(2_000L, 60_000L)
    LaunchedEffect(history, refreshMs) {
        history?.revision?.collect { r -> revision = r; delay(refreshMs) }
    }
    /** 表示の右端の時刻 (null = 現在に追従) */
    var endAt by remember { mutableStateOf<Long?>(null) }
    /** タップで選んだ時刻 (null = 現在値を表示) */
    var cursorAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(com.obdmeter.util.nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = com.obdmeter.util.nowMs()
        }
    }
    // 記録の一番古い時刻 (ここまでさかのぼれる)
    val earliest by produceState<Long?>(null, keys, revision) { value = history?.earliest(g, keys) }

    val end = endAt ?: now
    // 範囲を丸めて、動かすたびに読み直さないようにする
    val q = (span / 50).coerceAtLeast(1000L)
    // 記録の無い時間を詰める: 記録のある区間を調べる (表示の右端から 1 画面の 10 倍・最低 1 日さかのぼる)
    val gapMs = gapThreshold(look.chartIntervalMs, span)
    val segEnd = (end / q + 1) * q
    val segFrom = segEnd - maxOf(span * 10, 86_400_000L)
    val segs by produceState(emptyList<Pair<Long, Long>>(), keys, look.chartSkipGaps, gapMs, segFrom, segEnd, now / 15_000) {
        value = if (!look.chartSkipGaps || history == null) emptyList()
        else history.segments(g, keys, segFrom, segEnd, gapMs)
    }
    val axis = remember(segs, gapMs) { TimeAxis(segs, gapMs) }
    val cEnd = axis.toC(end)
    val cStart = cEnd - span
    val start = axis.toReal(cStart)
    val qStart = start / q * q - q
    val qEnd = (end / q + 1) * q
    val ranges = axis.overlaps(qStart, qEnd)
    val data by produceState(emptyMap<String, List<Sample>>(), keys, ranges, revision) {
        if (history != null) value = keys.associateWith { k ->
            // 表示範囲の外の直前・直後の点も入れて、端まで線がつながるようにする
            val (before, after) = history.neighbors(g, k, qStart, qEnd)
            val body = ranges.flatMap { (a, b) ->
                // 区間ごとに、詰めた幅に応じた点の数で読む
                val pts = (800L * (b - a + 1) / span.coerceAtLeast(1)).toInt().coerceIn(20, 800)
                history.load(g, k, a, b, pts)
            }
            listOfNotNull(before) + body + listOfNotNull(after)
        }
    }

    // タップした時刻に一番近い記録の時刻にそろえ、各項目はその時刻に一番近い記録の値を出す
    val cursorTime: Long? = cursorAt?.let { snapCursor(data, it) }
    val cursorValues: Map<String, Double?> = remember(cursorTime, data, keys, span, look.chartStep) {
        if (cursorTime == null) emptyMap()
        else if (look.chartStep) valuesAtStep(data, keys, cursorTime)
        else valuesAt(data, keys, cursorTime, span)
    }

    // 左右の縦軸の範囲 (属する項目の最小〜最大をまとめたもの。項目が無い軸は出さない)
    val axes = remember(plotSeries) { ChartAxes.of(plotSeries) }
    val axisW = 34.dp

    val legendPos = look.legendPosition
    val ls = look.legendScale
    /** 凡例: 各項目の現在値 (時刻を選んでいるときはその日時とその時刻の値) */
    val legend: @Composable (Modifier, Alignment.Horizontal) -> Unit = { m, align ->
        FlowRow(m, horizontalArrangement = Arrangement.spacedBy((10 * ls).dp, align)) {
            if (cursorTime != null) {
                Text(
                    com.obdmeter.util.DateFmt("yyyy/MM/dd HH:mm:ss").format(cursorTime),
                    color = GaugeColors.Accent, fontSize = (11 * look.labelSize * ls).sp, maxLines = 1,
                    fontWeight = FontWeight.Bold,
                )
            }
            series.forEachIndexed { i, s ->
                val shown = if (cursorTime != null) cursorValues[s.def.key] else s.value
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size((8 * ls).dp).clip(CircleShape).background(chartColor(look, i)))
                    Spacer(Modifier.width((4 * ls).dp))
                    Text(
                        s.def.shortName + " ",
                        color = look.labelColor, fontFamily = fonts.label,
                        fontSize = (11 * look.labelSize * ls).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        s.def.format(shown) + if (look.showUnit) " ${s.def.unitText}" else "",
                        color = chartColor(look, i), fontFamily = fonts.value, fontWeight = FontWeight.Bold,
                        fontSize = (13 * look.valueSize * ls).sp, maxLines = 1,
                        style = androidx.compose.ui.text.TextStyle(shadow = glowShadow(chartColor(look, i), look.glow, 30f)),
                    )
                }
            }
        }
    }
    val alignH = when (look.legendAlign) {
        com.obdmeter.data.LegendAlign.START -> Alignment.Start
        com.obdmeter.data.LegendAlign.CENTER -> Alignment.CenterHorizontally
        com.obdmeter.data.LegendAlign.END -> Alignment.End
    }
    val csv = interactive && history != null

    Column(modifier.padding(4.dp)) {
        if (legendPos == com.obdmeter.data.LegendPosition.TOP) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                legend(Modifier.weight(1f), alignH)
                if (csv) ChartCsvButtons(history!!, g, keys)
            }
        } else if (csv) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ChartCsvButtons(history!!, g, keys) }
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
                                val t = com.obdmeter.util.nowMs()
                                val oldest = earliest ?: t
                                val maxSpan = max(31 * 86_400_000L, t - oldest)
                                if (zoom != 1f) span = (span / zoom).toLong().coerceIn(60_000L, maxSpan)
                                if (pan.x != 0f && size.width > 0) {
                                    // 詰めた時間で動かす
                                    val baseC = axis.toC(endAt ?: t)
                                    val nowC = axis.toC(t)
                                    // 一番古い記録が左端に来るところまでさかのぼれる
                                    val minC = min(nowC, axis.toC(oldest) + span)
                                    val nextC = (baseC - (pan.x / size.width * span).toLong()).coerceIn(minC, nowC)
                                    endAt = if (nextC >= nowC - span / 100) null else axis.toReal(nextC)
                                }
                            }
                        }
                        .pointerInput(look.chartSpanMs) {
                            detectTapGestures(
                                // タップした位置の時刻の値を凡例に出す
                                onTap = { p ->
                                    val padL = if (axes.left != null) axisW.toPx() else 0f
                                    val padR = if (axes.right != null) axisW.toPx() else 0f
                                    val pw = size.width - padL - padR
                                    if (pw > 0) {
                                        val ec = axis.toC(endAt ?: com.obdmeter.util.nowMs())
                                        cursorAt = axis.toReal(ec - span + (((p.x - padL) / pw).coerceIn(0f, 1f) * span).toLong())
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
                val padL = if (axes.left != null) axisW.toPx() else 0f
                val padR = if (axes.right != null) axisW.toPx() else 0f
                val pw = w - padL - padR
                if (plotH <= 4f || pw <= 4f) return@Canvas
                fun xAt(t: Long) = padL + (axis.toC(t) - cStart).toFloat() / span * pw
                fun yAt(right: Boolean, v: Double): Float {
                    val (lo, hi) = (if (right) axes.right else axes.left) ?: (0.0 to 1.0)
                    val range = (hi - lo).takeIf { it > 0 } ?: 1.0
                    return plotH - ((v - lo) / range).toFloat().coerceIn(-0.02f, 1.02f) * plotH
                }

                // 横の補助線
                for (i in 0..4) {
                    val y = plotH * i / 4f
                    drawLine(GaugeColors.Track.copy(alpha = if (i == 0 || i == 4) 0.9f else 0.5f), Offset(padL, y), Offset(padL + pw, y), 1f)
                }
                // 左右の縦軸の目盛り (その軸の項目が 1 つならその項目の色)
                fun drawAxis(range: Pair<Double, Double>?, right: Boolean) {
                    val (lo, hi) = range ?: return
                    val members = plotSeries.indices.filter { plotSeries[it].right == right }
                    val color = if (members.size == 1) chartColor(look, members[0]) else GaugeColors.SubText
                    val st = labelStyle.copy(color = color)
                    val step = (hi - lo) / 4
                    val dec = when { step >= 5 -> 0; step >= 0.5 -> 1; else -> 2 }
                    for (i in 0..4) {
                        val v = hi - step * i
                        val lay = tm.measure("%.${dec}f".format(v), st)
                        val y = (plotH * i / 4f - lay.size.height / 2f).coerceIn(0f, max(0f, plotH - lay.size.height))
                        val x = if (right) padL + pw + 3f else (padL - 3f - lay.size.width).coerceAtLeast(0f)
                        drawText(lay, topLeft = Offset(x, y))
                    }
                }
                drawAxis(axes.left, false)
                drawAxis(axes.right, true)
                // 時刻の目盛り
                val step = timeStep(span)
                val fmt = com.obdmeter.util.DateFmt(if (step >= 21_600_000L) "M/d HH:mm" else "HH:mm")
                var lastLabelEnd = -1f
                for ((ra, rb) in axis.overlaps(start, end)) {
                    var t = ra - com.obdmeter.util.floorMod(ra + com.obdmeter.util.tzOffsetMs(ra), step) + step
                    while (t <= rb) {
                        val x = xAt(t)
                        drawLine(GaugeColors.Track.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, plotH), 1f)
                        val lay = tm.measure(fmt.format(t), labelStyle)
                        val lx = (x - lay.size.width / 2f).coerceIn(0f, max(0f, w - lay.size.width))
                        // 詰めたところで目盛りの文字が重なるときは出さない
                        if (lx > lastLabelEnd + 4f) {
                            drawText(lay, topLeft = Offset(lx, plotH + 2f))
                            lastLabelEnd = lx + lay.size.width
                        }
                        t += step
                    }
                }
                // 記録の無い時間を詰めたところ (区間の切れ目) に点線
                for (i in 1 until axis.segs.size) {
                    val bc = axis.cs[i] - axis.pad / 2
                    if (bc in cStart..cEnd) {
                        val x = padL + (bc - cStart).toFloat() / span * pw
                        drawLine(
                            GaugeColors.SubText.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, plotH), 1.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    }
                }

                // 項目ごとに、記録のある時刻を線でつなぐ (縦は属する軸の範囲で合わせる)
                var any = false
                plotSeries.forEachIndexed { i, s ->
                    val pts = data[s.def.key].orEmpty()
                    if (pts.isEmpty()) return@forEachIndexed
                    any = true
                    val color = chartColor(look, i)
                    fun x(p: Sample) = xAt(p.t)
                    fun y(p: Sample) = yAt(s.right, p.v.toDouble())
                    val path = Path()
                    pts.forEachIndexed { idx, p ->
                        if (idx == 0) path.moveTo(x(p), y(p))
                        else {
                            // 階段状: 次の記録の時刻まで前の値のまま横に引いてから縦に変える
                            if (look.chartStep) path.lineTo(x(p), y(pts[idx - 1]))
                            path.lineTo(x(p), y(p))
                        }
                    }
                    clipRect(padL, 0f, padL + pw, plotH) {
                        // 発光: 太く薄い線を下に重ねてにじませる
                        if (look.glow > 0f) {
                            val gw = 2.dp.toPx() + 8.dp.toPx() * look.glow
                            drawPath(path, color.copy(alpha = 0.15f * look.glow), style = Stroke(gw * 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            drawPath(path, color.copy(alpha = 0.30f * look.glow), style = Stroke(gw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        // 点がまばらなときは記録した位置に点を打つ
                        val visible = pts.count { axis.toC(it.t) in cStart..cEnd }
                        if (visible in 1..(pw / 8).toInt()) {
                            pts.forEach { p -> if (axis.toC(p.t) in cStart..cEnd) drawCircle(color, 2.5.dp.toPx(), Offset(x(p), y(p))) }
                        }
                    }
                }
                // 選んだ時刻の縦線
                cursorTime?.let { ct ->
                    if (axis.toC(ct) in cStart..cEnd) {
                        val cx = xAt(ct)
                        drawLine(GaugeColors.Accent, Offset(cx, 0f), Offset(cx, plotH), 1.5.dp.toPx())
                        plotSeries.forEachIndexed { i, s ->
                            val v = cursorValues[s.def.key] ?: return@forEachIndexed
                            val cy = yAt(s.right, v)
                            drawCircle(chartColor(look, i), 4.dp.toPx(), Offset(cx, cy))
                            drawCircle(GaugeColors.Text, 4.dp.toPx(), Offset(cx, cy), style = Stroke(1.dp.toPx()))
                        }
                    }
                }
                if (!any) {
                    drawCenteredText(tm, if (history == null) "" else "この期間の記録はありません", Offset(padL + pw / 2, plotH / 2), labelStyle)
                }
            }
            // グラフ内に置く凡例 (縦軸の目盛りと時刻の文字にかからない位置)
            if (legendPos.inside) {
                val padL = if (axes.left != null) axisW else 0.dp
                val padR = if (axes.right != null) axisW else 0.dp
                val top = legendPos == com.obdmeter.data.LegendPosition.INSIDE_TOP_LEFT ||
                    legendPos == com.obdmeter.data.LegendPosition.INSIDE_TOP_RIGHT
                val left = legendPos == com.obdmeter.data.LegendPosition.INSIDE_TOP_LEFT ||
                    legendPos == com.obdmeter.data.LegendPosition.INSIDE_BOTTOM_LEFT
                legend(
                    Modifier
                        .align(
                            if (top) { if (left) Alignment.TopStart else Alignment.TopEnd }
                            else { if (left) Alignment.BottomStart else Alignment.BottomEnd }
                        )
                        .padding(start = padL + 2.dp, end = padR + 2.dp, top = 2.dp, bottom = 16.dp)
                        .widthIn(max = maxWidth - padL - padR)
                        .background(Color.Black.copy(alpha = look.legendBgAlpha), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    if (left) Alignment.Start else Alignment.End,
                )
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
        if (legendPos == com.obdmeter.data.LegendPosition.BOTTOM) {
            legend(Modifier.fillMaxWidth().padding(top = 2.dp), alignH)
        }
    }
}

/** グラフの記録の CSV 書き出し・読み込みボタン (アプリの画面でだけ出す) */
@Composable
internal fun ChartCsvButtons(history: HistoryStore, slot: String, keys: List<String>) {
    val toast = com.obdmeter.ui.rememberToast()
    val scope = rememberCoroutineScope()
    val exporter = com.obdmeter.ui.rememberFileSaver("text/csv") { target ->
        scope.launch {
            val msg = runCatching {
                val (n, t) = history.exportCsvText(slot, keys)
                val where = target.write(t.encodeToByteArray())
                "$n 件を" + (where?.let { " $it に保存しました" } ?: "書き出しました")
            }.getOrElse { "書き出しに失敗しました: ${it.message}" }
            toast(msg)
        }
    }
    val importer = com.obdmeter.ui.rememberFileOpener(listOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) { f ->
        scope.launch {
            val msg = runCatching {
                val (ok, bad) = history.importCsvText(slot, f.read().decodeToString())
                "$ok 件を読み込みました" + if (bad > 0) " (読めない行 $bad 件)" else ""
            }.getOrElse { "読み込みに失敗しました: ${it.message}" }
            toast(msg)
        }
    }
    Row {
        CsvButton("書出") {
            val stamp = com.obdmeter.util.DateFmt("yyyyMMdd_HHmm").format(com.obdmeter.util.nowMs())
            exporter("obdmeter_chart_$stamp.csv")
        }
        Spacer(Modifier.width(4.dp))
        CsvButton("読込") {
            if (!importer()) toast("この端末ではファイルを選ぶ画面を開けません")
        }
    }
}

@Composable
internal fun CsvButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(GaugeColors.Card.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { Text(text, fontSize = 11.sp, color = GaugeColors.Text) }
}
