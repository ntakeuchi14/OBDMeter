package com.obdmeter.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.requiredSize
import com.obdmeter.data.HistoryStore
import com.obdmeter.data.historyRetentionMs
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.obdmeter.ui.gauges.chartColor
import com.obdmeter.core.Condition
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.RadioButton
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdmeter.data.FontEntry
import com.obdmeter.data.GaugeFonts
import com.obdmeter.ui.gauges.LocalFontLibrary
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.obdmeter.core.PidCatalog
import com.obdmeter.data.GaugeConfig
import com.obdmeter.data.GaugeDesign
import com.obdmeter.data.GaugeImages
import com.obdmeter.data.GaugeStyle
import com.obdmeter.data.Layout
import com.obdmeter.data.RingStyle
import com.obdmeter.ui.gauges.GaugeCard
import com.obdmeter.ui.gauges.GaugeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * メーター 1 個のデザイン編集 (全画面)。上 (横画面では左) にプレビュー、下に設定。
 * 変更は下書きに入れて少し遅れて保存する (スライダー操作が重くならないように)。
 */
@Composable
fun GaugeSettingsDialog(
    vm: MainViewModel,
    gauge: GaugeConfig,
    catalog: PidCatalog,
    supported: Set<Int>?,
    liveValue: Double?,
    /** プレビューのグラフに出す記録 */
    historySlot: String?,
    onChange: (GaugeConfig) -> Unit,
    onBringToFront: () -> Unit,
    onDelete: () -> Unit,
    onCreateCustomPid: () -> Unit,
    /** 重なりの順番 (+1 = 1 つ手前、-1 = 1 つ奥、MAX = 最前面、MIN = 最背面) */
    onMoveZ: (Int) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var draft by remember(gauge.id) { mutableStateOf(gauge) }
    val context = LocalContext.current
    val cellAspect by vm.cellAspect.collectAsState()
    val cellSize by vm.cellSizeDp.collectAsState()
    val latestOnChange by rememberUpdatedState(onChange)
    // 下書きを 250ms 遅れで保存
    LaunchedEffect(draft) {
        if (draft != gauge) {
            delay(250)
            latestOnChange(draft)
        }
    }
    val close = {
        if (draft != gauge) onChange(draft)
        onDismiss()
    }

    var tab by remember { mutableStateOf(EditorTab.ITEM) }
    var showPicker by remember { mutableStateOf(false) }
    var showFallbackPicker by remember { mutableStateOf(false) }
    var showChartPicker by remember { mutableStateOf(false) }
    /** フォント選択ダイアログの対象 ("value" / "label")。null = 閉じる */
    var fontPickerFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val def = draft.resolvedDef(catalog)
    var useLive by remember { mutableStateOf(false) }
    var previewFraction by remember { mutableFloatStateOf(0.65f) }
    val previewValue = if (useLive) liveValue else def?.let { it.min + (it.max - it.min) * previewFraction }

    fun update(transform: (GaugeDesign) -> GaugeDesign) {
        draft = draft.copy(design = transform(draft.design))
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(GaugeColors.Background).safeDrawingPadding()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth > maxHeight
                val previewHeight = maxHeight * 0.38f
                val preview: @Composable (Modifier) -> Unit = { m ->
                    Column(m.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            // ダッシュボードで実際に表示する大きさ (dp) で描いてから、枠に収まるように拡大縮小する。
                            // 文字の大きさ (sp)・余白 (dp) と、メーターの大きさとの比率まで実物と同じになる
                            val ratio = (draft.w.coerceAtLeast(1) * cellAspect / draft.h.coerceAtLeast(1)).coerceIn(0.2f, 6f)
                            val real = cellSize?.let { (cw, ch) ->
                                // ダッシュボードのメーターは周りに 3dp ずつ空けて置く
                                (cw * draft.w - 6f).coerceAtLeast(10f) to (ch * draft.h - 6f).coerceAtLeast(10f)
                            }
                            val previewModifier = if (real != null) {
                                val k = minOf(maxWidth.value / real.first, maxHeight.value / real.second)
                                Modifier.requiredSize(real.first.dp, real.second.dp).graphicsLayer { scaleX = k; scaleY = k }
                            } else Modifier.aspectRatio(ratio)
                            if (def != null) {
                                GaugeCard(
                                    def = def,
                                    value = previewValue,
                                    style = draft.style,
                                    unsupported = false,
                                    look = draft.look(def),
                                    modifier = previewModifier,
                                    series = if (draft.style == GaugeStyle.CHART || draft.style == GaugeStyle.TABLE || draft.style == GaugeStyle.MONITOR) {
                                        chartSeries(draft, def, mapOf(def.key to (previewValue ?: 0.0)), catalog)
                                    } else emptyList(),
                                    historySlot = historySlot,
                                )
                            } else {
                                Text("項目が見つかりません", color = GaugeColors.SubText)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("プレビュー値", fontSize = 12.sp, color = GaugeColors.SubText)
                            Slider(
                                value = previewFraction,
                                onValueChange = { previewFraction = it; useLive = false },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            FilterChip(useLive, { useLive = !useLive }, { Text("実測値") })
                        }
                    }
                }
                // 表示形式ごとのタブ (その形式で使わない設定は出さない)
                val tabs = EditorTabs.forStyle(draft.style)
                val currentTab = EditorTabs.keepOrFirst(draft.style, tab)
                val onPickFont: (String) -> Unit = { target -> fontPickerFor = target }
                val settings: @Composable (Modifier) -> Unit = { m ->
                    Column(m) {
                        ScrollableTabRow(selectedTabIndex = tabs.indexOf(currentTab), edgePadding = 8.dp) {
                            tabs.forEach { t ->
                                Tab(selected = currentTab == t, onClick = { tab = t }, text = { Text(t.label) })
                            }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            when (currentTab) {
                                EditorTab.ITEM, EditorTab.ITEM_AXIS -> ItemTab(draft, catalog, ::update, { draft = it }, { showPicker = true },
                                    { showFallbackPicker = true }, { showChartPicker = true })
                                EditorTab.SCALE -> ScaleTab(draft, ::update)
                                EditorTab.BAR -> BarTab(draft, def, ::update)
                                EditorTab.LAMP -> LampTab(draft, ::update, onPickFont)
                                EditorTab.TABLE -> TableTab(draft, ::update, onPickFont)
                                EditorTab.VIEW -> if (draft.style == GaugeStyle.CHART) ChartViewTab(draft, ::update, onPickFont)
                                    else MonitorViewTab(draft, ::update, onPickFont)
                                EditorTab.RECORD -> ChartRecordTab(draft, catalog, ::update)
                                EditorTab.LOOK -> LookTab(draft, ::update, onPickFont)
                                EditorTab.IMAGE -> ImageTab(draft, ::update)
                                EditorTab.PLACE -> PlaceTab(draft, { draft = it }, {
                                    vm.copyGauge(draft)
                                    Toast.makeText(context, "コピーしました。下の「貼り付け」で、このページや別のページに貼り付けられます", Toast.LENGTH_LONG).show()
                                }, onMoveZ) { confirmDelete = true }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = close) { Icon(Icons.Default.Close, "閉じる") }
                        Text("メーターのデザイン", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = close, modifier = Modifier.padding(end = 8.dp)) { Text("完了") }
                    }
                    if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            preview(Modifier.weight(1f).fillMaxHeight())
                            settings(Modifier.weight(1.1f).fillMaxHeight())
                        }
                    } else {
                        preview(Modifier.fillMaxWidth().height(previewHeight))
                        settings(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }

        fontPickerFor?.let { target ->
            FontPickerDialog(
                vm = vm,
                title = if (target == "value") "数値のフォント" else "文字のフォント",
                selected = if (target == "value") draft.design.valueFont else draft.design.labelFont,
                onPick = { id ->
                    update { if (target == "value") it.copy(valueFont = id) else it.copy(labelFont = id) }
                    fontPickerFor = null
                },
                onDismiss = { fontPickerFor = null },
            )
        }
        if (showFallbackPicker) {
            ItemPickerDialog(
                catalog = catalog,
                supported = supported,
                onPick = { picked ->
                    draft = draft.copy(fallbackKey = picked.key.takeIf { it != draft.pidKey })
                    showFallbackPicker = false
                },
                onCreateCustom = { showFallbackPicker = false; close(); onCreateCustomPid() },
                onDismiss = { showFallbackPicker = false },
            )
        }
        if (showChartPicker) {
            ItemPickerDialog(
                catalog = catalog,
                supported = supported,
                onPick = { picked ->
                    if (picked.key !in draft.chartAllKeys) draft = draft.copy(chartKeys = draft.chartKeys + picked.key)
                    showChartPicker = false
                },
                onCreateCustom = { showChartPicker = false; close(); onCreateCustomPid() },
                onDismiss = { showChartPicker = false },
            )
        }
        if (showPicker) {
            ItemPickerDialog(
                catalog = catalog,
                supported = supported,
                onPick = { picked ->
                    // 項目を変えたら、前の項目用の範囲・表示名・レッドゾーンなどはリセットする
                    draft = draft.copy(
                        pidKey = picked.key,
                        design = draft.design.copy(
                            label = null, min = null, max = null, decimals = null, useDefaultWarn = true,
                            warnAbove = null, warnBelow = null, redZoneFrom = null, redZoneTo = null,
                            majorDivisions = null,
                        ),
                    )
                    showPicker = false
                },
                onCreateCustom = { showPicker = false; close(); onCreateCustomPid() },
                onDismiss = { showPicker = false },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("このメーターを削除しますか？") },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("削除", color = GaugeColors.Warn) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// タブ: 項目 (表示する項目・表示形式・範囲など。値テーブル・グラフ・変動監視は並べる項目も)
// ---------------------------------------------------------------------------

@Composable
private fun ItemTab(
    g: GaugeConfig,
    catalog: PidCatalog,
    update: ((GaugeDesign) -> GaugeDesign) -> Unit,
    replace: (GaugeConfig) -> Unit,
    onChangeItem: () -> Unit,
    onPickFallback: () -> Unit,
    onAddChartItem: () -> Unit,
) {
    val base = catalog.get(g.pidKey)
    val d = g.design
    Head("表示形式")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        GaugeStyle.entries.forEach { s ->
            FilterChip(g.style == s, { replace(g.copy(style = s)) }, { Text(s.label) }, Modifier.padding(end = 6.dp))
        }
    }
    Hint("表示形式を変えると、その形式で使う設定のタブに切り替わります (ほかの形式の設定は残っています)。")

    Head("表示する項目")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(base?.name ?: "(見つかりません: ${g.pidKey})", fontWeight = FontWeight.Medium)
            if (base != null) Text("${base.sourceLabel()}  ${base.unit}", color = GaugeColors.SubText, fontSize = 12.sp)
        }
        OutlinedButton(onClick = onChangeItem) { Text("変更") }
    }

    when (g.style) {
        GaugeStyle.TABLE -> {
            Head("表に並べる項目")
            Hint("上から順に並べ、1 段の最大行数を超えたら右に段を増やします。先頭は上の「表示する項目」です。")
            ItemRows(g, catalog, update, replace, onAddChartItem)
        }
        GaugeStyle.MONITOR -> {
            Head("監視する項目")
            Hint(
                "各項目の値が変わった回数と時刻・値を記録し、直近の期間ごとに変動回数の多い順 (同じ回数なら登録順) に並べます。" +
                    "先頭は上の「表示する項目」です。"
            )
            ItemRows(g, catalog, update, replace, onAddChartItem)
        }
        GaugeStyle.CHART -> ChartItems(g, catalog, update, replace, onAddChartItem)
        else -> {}
    }

    if (g.style != GaugeStyle.MONITOR) {
        Head("代替項目 (イグニッション OFF 時など)")
        Hint(
            "上の項目が読めないときに、代わりに表示する項目です。BMW ではイグニッション OFF の間、" +
                "標準の項目 (水温・電圧など) はエンジン ECU が応答を拒否します。" +
                "同じ値を読むカスタム PID を指定しておくと、OFF の間はそちらの値を表示します。" +
                "単位と範囲は上の項目に合わせてください。"
        )
        val fb = g.fallbackKey?.let { catalog.get(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    g.fallbackKey == null -> Text("なし", color = GaugeColors.SubText)
                    fb == null -> Text("(見つかりません: ${g.fallbackKey})", color = GaugeColors.Warn)
                    else -> {
                        Text(fb.name, fontWeight = FontWeight.Medium)
                        Text("${fb.sourceLabel()}  ${fb.unit}", color = GaugeColors.SubText, fontSize = 12.sp)
                    }
                }
            }
            OutlinedButton(onClick = onPickFallback) { Text(if (g.fallbackKey == null) "設定" else "変更") }
            if (g.fallbackKey != null) {
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { replace(g.copy(fallbackKey = null)) }) { Text("外す") }
            }
        }
    }

    if (EditorTabs.usesLabelField(g.style)) {
        Head("表示名 (空欄で「${base?.shortName ?: ""}」)")
        var labelText by remember(g.id, g.pidKey) { mutableStateOf(d.label.orEmpty()) }
        OutlinedTextField(
            value = labelText,
            onValueChange = { labelText = it; update { x -> x.copy(label = it.ifBlank { null }) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (EditorTabs.usesRange(g.style)) {
        Head(if (g.style == GaugeStyle.CHART) "先頭の項目の範囲 (空欄で既定値)" else "目盛りの範囲 (空欄で既定値)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("最小", d.min, base?.min, g.id to g.pidKey, Modifier.weight(1f)) { v -> update { it.copy(min = v) } }
            NumberField("最大", d.max, base?.max, g.id to g.pidKey, Modifier.weight(1f)) { v -> update { it.copy(max = v) } }
        }
    }

    Head("小数点以下の桁数")
    Row {
        listOf<Int?>(null, 0, 1, 2, 3).forEach { n ->
            FilterChip(d.decimals == n, { update { it.copy(decimals = n) } }, { Text(n?.toString() ?: "既定") },
                Modifier.padding(end = 4.dp))
        }
    }

    if (EditorTabs.usesWarn(g.style)) {
        SwitchLine("既定の警告値を使う",
            listOfNotNull(base?.warnAbove?.let { "上限 ${fmt(it)}" }, base?.warnBelow?.let { "下限 ${fmt(it)}" })
                .joinToString(" / ").ifEmpty { "警告なし" },
            d.useDefaultWarn) { v -> update { it.copy(useDefaultWarn = v) } }
        if (!d.useDefaultWarn) {
            Hint(if (g.style == GaugeStyle.LAMP) "条件が空欄のランプは、値がこの範囲を超えると点灯します (空欄 = 警告なし)"
                else "値がこの範囲を超えると針・数値が赤くなります (空欄 = 警告なし)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("下限", d.warnBelow, null, g.id to g.pidKey, Modifier.weight(1f)) { v -> update { it.copy(warnBelow = v) } }
                NumberField("上限", d.warnAbove, null, g.id to g.pidKey, Modifier.weight(1f)) { v -> update { it.copy(warnAbove = v) } }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// タブ: 配置 (全形式共通)
// ---------------------------------------------------------------------------

@Composable
private fun PlaceTab(
    g: GaugeConfig,
    replace: (GaugeConfig) -> Unit,
    onCopy: () -> Unit,
    onMoveZ: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Head("位置と大きさ")
    Text("位置 (${g.x}, ${g.y})  大きさ ${g.w} × ${g.h} (ページ全体 ${Layout.COLS} × ${Layout.ROWS}、1 マス = ${Layout.SCALE})",
        color = GaugeColors.SubText, fontSize = 12.sp)
    Hint("ページの外にはみ出して置けます (はみ出した部分は表示されません)。右や下にはみ出して大きさ変更のつまみが見えないときは、ここで調整してください。")
    fun place(x: Int, y: Int, w: Int, h: Int) {
        val (cx, cy, cw, ch) = Layout.clampPlacement(x, y, w, h)
        replace(g.copy(x = cx, y = cy, w = cw, h = ch))
    }
    fun IntRange.f() = first.toFloat()..last.toFloat()
    SliderLine("横の位置", g.x.toFloat(), Layout.xRange(g.w).f(), "%.0f") { v -> place(v.roundToInt(), g.y, g.w, g.h) }
    SliderLine("縦の位置", g.y.toFloat(), Layout.yRange(g.h).f(), "%.0f") { v -> place(g.x, v.roundToInt(), g.w, g.h) }
    SliderLine("幅", g.w.toFloat(), Layout.widthRange.f(), "%.0f") { v -> place(g.x, g.y, v.roundToInt(), g.h) }
    SliderLine("高さ", g.h.toFloat(), Layout.heightRange.f(), "%.0f") { v -> place(g.x, g.y, g.w, v.roundToInt()) }

    Head("グループ")
    if (g.layoutGroup != null) {
        Hint("このメーターはグループに入っています。ダッシュボードではグループごと移動・大きさ変更・コピーされます (ここでの位置・大きさの変更とコピーは、このメーターだけ)。")
        OutlinedButton(onClick = { replace(g.copy(layoutGroup = null)) }) { Text("グループから外す") }
    } else {
        Hint("グループには入っていません。ダッシュボードの編集中に下の「グループ」から、複数のメーターをまとめられます。")
    }

    Head("フロート表示")
    SwitchLine("フロート表示する", "OFF にすると、このメーターはダッシュボードだけに表示し、フロート表示には出しません",
        g.floatShow) { replace(g.copy(floatShow = it)) }
    if (g.floatShow) {
        Hint(
            "同じページで同じフロート表示のグループにしたメーターは、1 つのウィンドウにまとめて、" +
                "ダッシュボードと同じ並びで表示します。「なし」のメーターは 1 つずつ表示します。"
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(g.floatGroup == null, { replace(g.copy(floatGroup = null)) }, { Text("なし") }, Modifier.padding(end = 6.dp))
            (1..GaugeConfig.FLOAT_GROUPS).forEach { n ->
                FilterChip(g.floatGroup == n, { replace(g.copy(floatGroup = n)) }, { Text(GaugeConfig.groupLabel(n)) },
                    Modifier.padding(end = 6.dp))
            }
        }
    }

    Head("重なりの順番")
    Hint("メーターが重なっているときに、どちらを手前に描くかです。ダッシュボードの編集中にメーターをダブルタップしても最前面にできます。")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onMoveZ(Int.MAX_VALUE) }) { Text("最前面へ") }
        OutlinedButton(onClick = { onMoveZ(1) }) { Text("1 つ手前へ") }
        OutlinedButton(onClick = { onMoveZ(-1) }) { Text("1 つ奥へ") }
        OutlinedButton(onClick = { onMoveZ(Int.MIN_VALUE) }) { Text("最背面へ") }
    }

    Head("操作")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = onCopy) { Text(if (g.layoutGroup != null) "このメーターだけコピー" else "コピー") }
        OutlinedButton(onClick = onDelete) { Text("削除", color = GaugeColors.Warn) }
    }
}

// ---------------------------------------------------------------------------
// タブ: 目盛り・針 (アナログ)
// ---------------------------------------------------------------------------

@Composable
private fun ScaleTab(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    val d = g.design
    Head("シンプルな扇形")
    Hint("針・目盛りを消して、端の丸い太い弧と中央の大きな数値だけのシンプルなメーターにします (あとから個別に調整できます)。")
    OutlinedButton(onClick = {
        update {
            it.copy(
                startAngle = -135f, endAngle = 135f, showNeedle = false, showTicks = false, showLabels = false,
                ring = RingStyle.NONE, showTrack = true, showValueArc = true, arcWidth = 2.2f, tickRadius = 1f,
                arcRoundCap = true, arcTipDot = true, trackColor = 0xFF2A2F37L, color = it.color ?: 0xFF2F9BFFL,
                valuePosition = 0.05f, valueOffsetX = 0f, valueSize = 2.2f,
                nameOffsetX = 0f, nameOffsetY = 0.51f, unitOffsetX = 0f, unitOffsetY = 0f,
                showName = true, showUnit = true,
            )
        }
    }) { Text("シンプルな扇形にする") }

    AngleSection(d, update)

    Head("目盛り")
    SwitchLine("目盛りの線を表示", null, d.showTicks) { v -> update { it.copy(showTicks = v) } }
    SwitchLine("主目盛りの数を自動で決める", null, d.majorDivisions == null) { auto ->
        update { it.copy(majorDivisions = if (auto) null else 8) }
    }
    d.majorDivisions?.let { n ->
        SliderLine("主目盛りの区間数", n.toFloat(), 1f..20f, "%.0f") { v -> update { it.copy(majorDivisions = v.roundToInt()) } }
    }
    SliderLine("小目盛り (主目盛りの間)", d.minorDivisions.toFloat(), 0f..9f, "%.0f") { v ->
        update { it.copy(minorDivisions = v.roundToInt()) }
    }
    SliderLine("目盛りの位置 (外周 = 100%)", d.tickRadius * 100, 40f..110f, "%.0f%%") { v ->
        update { it.copy(tickRadius = snap(v, 1f) / 100f) }
    }
    SwitchLine("目盛りの数字を表示", null, d.showLabels) { v -> update { it.copy(showLabels = v) } }
    if (d.showLabels) {
        SliderLine("数字の大きさ", d.labelSize * 100, 40f..250f, "%.0f%%") { v -> update { it.copy(labelSize = snap(v, 5f) / 100f) } }
    }
    ColorLine("目盛りの色", d.tickColor, GaugeColors.Tick) { c -> update { it.copy(tickColor = c) } }

    Head("弧")
    SwitchLine("下地の弧", null, d.showTrack) { v -> update { it.copy(showTrack = v) } }
    if (d.showTrack) ColorLine("下地の弧の色 (既定 = 灰色)", d.trackColor, GaugeColors.Track) { c -> update { it.copy(trackColor = c) } }
    SwitchLine("現在値の弧", null, d.showValueArc) { v -> update { it.copy(showValueArc = v) } }
    SliderLine("弧の太さ (0 = なし)", d.arcWidth * 100, 0f..300f, "%.0f%%") { v -> update { it.copy(arcWidth = snap(v, 5f) / 100f) } }
    SwitchLine("弧の端を丸くする", null, d.arcRoundCap) { v -> update { it.copy(arcRoundCap = v) } }
    SwitchLine("弧の先に丸い点を付ける", "ピーク値を表示しているときは、点がピーク値の印になります (三角の印の代わり)", d.arcTipDot) { v ->
        update { it.copy(arcTipDot = v) }
    }
    if (d.arcTipDot) {
        if (d.showPeak) Hint("ピーク値を表示しているので、点の色は「ピーク値の印の色」になります (「色・数値」タブ)")
        else ColorLine("点の色", d.arcTipColor, Color.White) { c -> update { it.copy(arcTipColor = c) } }
    }

    RedZoneSection(g, update)

    Head("針 (ポインター)")
    SwitchLine("針を表示", null, d.showNeedle) { v -> update { it.copy(showNeedle = v) } }
    if (d.showNeedle) {
        ColorLine("針の色", d.needleColor, GaugeColors.Text) { c -> update { it.copy(needleColor = c) } }
        SliderLine("針の長さ", d.needleLength * 100, 30f..130f, "%.0f%%") { v -> update { it.copy(needleLength = snap(v, 5f) / 100f) } }
        SliderLine("針の太さ", d.needleWidth * 100, 20f..400f, "%.0f%%") { v -> update { it.copy(needleWidth = snap(v, 5f) / 100f) } }
        SliderLine("反対側の尾の長さ", d.needleTail * 100, 0f..60f, "%.0f%%") { v -> update { it.copy(needleTail = snap(v, 1f) / 100f) } }

        Head("中心のノブ")
        ColorLine("ノブの色 (既定 = 針と同じ)", d.knobColor, d.needleColor?.let { Color(it) } ?: GaugeColors.Text) { c ->
            update { it.copy(knobColor = c) }
        }
        SliderLine("ノブの大きさ (0 = なし)", d.knobSize * 100, 0f..400f, "%.0f%%") { v -> update { it.copy(knobSize = snap(v, 5f) / 100f) } }
    }
}

/** 開始・終了角度 (アナログ・弧のバー) */
@Composable
private fun AngleSection(d: GaugeDesign, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    Head("開始・終了角度 (12 時 = 0°、時計回り)")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(
            "標準 240°" to (-120f to 120f),
            "270°" to (-135f to 135f),
            "300°" to (-150f to 150f),
            "半円 180°" to (-90f to 90f),
            "右上 90°" to (0f to 90f),
            "全周 360°" to (-180f to 180f),
        ).forEach { (name, a) ->
            FilterChip(d.startAngle == a.first && d.endAngle == a.second,
                { update { it.copy(startAngle = a.first, endAngle = a.second) } }, { Text(name) }, Modifier.padding(end = 6.dp))
        }
    }
    SliderLine("開始角度", d.startAngle, -180f..180f, "%.0f°") { v -> update { it.copy(startAngle = snap(v, 5f)) } }
    SliderLine("終了角度", d.endAngle, -180f..360f, "%.0f°") { v -> update { it.copy(endAngle = snap(v, 5f)) } }
}

/** レッドゾーン (アナログ・バー) */
@Composable
private fun RedZoneSection(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    val d = g.design
    Head("レッドゾーン")
    SwitchLine("レッドゾーンを表示", null, d.showRedZone) { v -> update { it.copy(showRedZone = v) } }
    if (d.showRedZone) {
        Hint("空欄なら警告値 (上限〜最大、または最小〜下限) を使います")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("開始", d.redZoneFrom, null, g.id to g.pidKey, Modifier.weight(1f)) { v -> update { it.copy(redZoneFrom = v) } }
            NumberField("終了", d.redZoneTo, null, g.id to g.pidKey, Modifier.weight(1f)) { v -> update { it.copy(redZoneTo = v) } }
        }
        ColorLine("レッドゾーンの色", d.redZoneColor, GaugeColors.WarnZone, extra = listOf("濃い赤" to 0xFFD32F2F)) { c ->
            update { it.copy(redZoneColor = c) }
        }
    }
}

// ---------------------------------------------------------------------------
// タブ: バーの形 (バー)
// ---------------------------------------------------------------------------

@Composable
private fun BarTab(g: GaugeConfig, def: com.obdmeter.core.PidDef?, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    val d = g.design
    Head("デジパネ風")
    Hint("昔のソアラや 86 のデジタルメーターのような、弧を描いて伸びる蛍光管風のバーにします (あとから個別に調整できます)。")
    OutlinedButton(onClick = {
        update {
            it.copy(
                barArc = true, startAngle = -100f, endAngle = 70f, barSegments = 32, barSegmentGap = 0.35f,
                barTaper = 0.65f, arcWidth = 1.2f, showTrack = true, showValueArc = true,
                barZoneColors = true, color = 0xFF39FF7AL, barMidColor = 0xFFFFC21AL,
                barMidFrom = def?.let { d0 -> d0.min + (d0.max - d0.min) * 0.7 },
                redZoneColor = 0xFFFF3B30L, barOffColor = null,
                glow = 0.6f, ghostDigits = true, ghostAlpha = 0.12f,
                valueColor = 0xFF5CFFB0L, faceColor = it.faceColor ?: 0xFF050805L,
            )
        }
    }) { Text("デジパネ風にする") }

    Head("バーの形")
    Row {
        FilterChip(!d.barArc, { update { it.copy(barArc = false) } }, { Text("直線") }, Modifier.padding(end = 6.dp))
        FilterChip(d.barArc, { update { it.copy(barArc = true) } }, { Text("弧") })
    }
    if (d.barArc) {
        Hint("枠いっぱいに収まる大きさで描きます。")
        AngleSection(d, update)
    } else {
        Head("バーの角度 (時計回り)")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(
                "横 (左→右)" to 0f, "縦 (下→上)" to -90f, "縦 (上→下)" to 90f,
                "斜め ↗" to -45f, "斜め ↘" to 45f, "横 (右→左)" to 180f,
            ).forEach { (name, a) ->
                FilterChip(d.barAngle == a, { update { it.copy(barAngle = a) } }, { Text(name) }, Modifier.padding(end = 6.dp))
            }
        }
        SliderLine("角度", d.barAngle, -180f..180f, "%.0f°") { v -> update { it.copy(barAngle = snap(v, 5f)) } }
        Hint("0° 以外はメーターの中央を軸に回し、枠に収まる長さで描きます。")
    }

    Head("バーの線")
    Row {
        FilterChip(d.barSegments == 0, { update { it.copy(barSegments = 0) } }, { Text("実線") }, Modifier.padding(end = 6.dp))
        FilterChip(d.barSegments > 0, { update { it.copy(barSegments = if (it.barSegments > 0) it.barSegments else 20) } },
            { Text("破線") })
    }
    if (d.barSegments > 0) {
        SliderLine("マスの数", d.barSegments.toFloat(), 3f..60f, "%.0f") { v -> update { it.copy(barSegments = v.roundToInt()) } }
        SliderLine("マスの間のすき間", d.barSegmentGap * 100, 5f..80f, "%.0f%%") { v -> update { it.copy(barSegmentGap = snap(v, 1f) / 100f) } }
    }
    SliderLine("バーの太さ", d.arcWidth * 100, 0f..300f, "%.0f%%") { v -> update { it.copy(arcWidth = snap(v, 5f) / 100f) } }
    SliderLine("先細り", d.barTaper * 100, 0f..100f, "%.0f%%") { v -> update { it.copy(barTaper = snap(v, 5f) / 100f) } }
    Hint("始まりを細く、終わりに向かって太くします (デジパネのタコメーター風)。")
    SwitchLine("下地 (値の無い部分) を表示", null, d.showTrack) { v -> update { it.copy(showTrack = v) } }
    SwitchLine("現在値のバーを表示", null, d.showValueArc) { v -> update { it.copy(showValueArc = v) } }

    Head("マスの色")
    ColorLine("通常の色 (= メーターの色)", d.color, GaugeColors.Accent) { c -> update { it.copy(color = c) } }
    SwitchLine("範囲で色分けする", "通常の色 → 中間の色 → レッドゾーンの色 の順に、位置ごとに塗り分けます", d.barZoneColors) { v ->
        update { it.copy(barZoneColors = v) }
    }
    if (d.barZoneColors) {
        ColorLine("中間の色", d.barMidColor, Color(0xFFFFC107)) { c -> update { it.copy(barMidColor = c) } }
        if (def != null && def.max > def.min) {
            val from = (d.barMidFrom ?: (def.min + (def.max - def.min) * 0.7)).toFloat()
            SliderLine("中間の色にする値", from, def.min.toFloat()..def.max.toFloat(), "%.0f") { v ->
                update { it.copy(barMidFrom = v.toDouble()) }
            }
        }
        Hint("レッドゾーンの範囲と色は下の「レッドゾーン」の設定を使います。")
    }
    ColorLine("消えているマス・背景の色 (既定 = 自動)", d.barOffColor, GaugeColors.Track) { c -> update { it.copy(barOffColor = c) } }
    Hint("自動のときは、色分けか発光を使うと各マスの色をうっすら、それ以外は灰色で描きます。")
    ColorLine("目盛りの色", d.tickColor, GaugeColors.Tick) { c -> update { it.copy(tickColor = c) } }

    RedZoneSection(g, update)
}

// ---------------------------------------------------------------------------
// タブ: 色・数値 (アナログ・バー・デジタル)
// ---------------------------------------------------------------------------

@Composable
private fun LookTab(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit, onPickFont: (String) -> Unit) {
    val d = g.design
    FontSection(d, onPickFont)
    if (g.style == GaugeStyle.ANALOG) {
        ColorLine("メーターの色 (現在値の弧)", d.color, GaugeColors.Accent) { c -> update { it.copy(color = c) } }
    }
    GlowSection(g, update)
    FaceSection(d, update)
    if (EditorTabs.usesRing(g.style)) RingSection(d, update)

    Head("値の動き")
    SliderLine("なめらかさ", d.smoothing * 100, 0f..100f, "%.0f%%") { v -> update { it.copy(smoothing = snap(v, 5f) / 100f) } }
    Hint("小さくするほど値の変化にすぐ追従し (反応が速い)、大きくするほど針や数値がなめらかに動きます (少し遅れます)。50% が従来の動きです。")

    PeakSection(g, update)
    ValueNameUnitSection(g, update)
    CardSwitch(d, update, g.style)
}

@Composable
private fun FontSection(d: GaugeDesign, onPickFont: (String) -> Unit) {
    val lib = LocalFontLibrary.current
    Head("フォント")
    Hint("取り込んだフォントは、ほかのメーターでもそのまま選べます")
    FontLine("数値", d.valueFont?.let { lib[it]?.name ?: "(削除されたフォント)" } ?: "既定 (等幅)") { onPickFont("value") }
    FontLine("文字 (名前・単位・目盛りの数字)", d.labelFont?.let { lib[it]?.name ?: "(削除されたフォント)" } ?: "既定") { onPickFont("label") }
}

/** 蛍光管風 (発光・消えている 7 セグ) */
@Composable
private fun GlowSection(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    val d = g.design
    Head("蛍光管風")
    SliderLine("発光 (にじみ)", d.glow * 100, 0f..100f, "%.0f%%") { v -> update { it.copy(glow = snap(v, 5f) / 100f) } }
    if (EditorTabs.usesGhost(g.style)) {
        SwitchLine("消えている 7 セグを薄く表示", "数値の後ろに「888」をうっすら出します (7 セグ風のフォントと合わせて使います)", d.ghostDigits) { v ->
            update { it.copy(ghostDigits = v) }
        }
        if (d.ghostDigits) {
            SliderLine("薄さ", d.ghostAlpha * 100, 3f..60f, "%.0f%%") { v -> update { it.copy(ghostAlpha = snap(v, 1f) / 100f) } }
        }
    }
}

/** メーターの背景色 */
@Composable
private fun FaceSection(d: GaugeDesign, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    ColorLine("メーターの背景色 (既定 = なし)", d.faceColor, Color.Transparent) { c -> update { it.copy(faceColor = c) } }
    if (d.faceColor != null) {
        SliderLine("背景色の大きさ", d.faceScale * 100, 20f..150f, "%.0f%%") { v -> update { it.copy(faceScale = snap(v, 1f) / 100f) } }
        if (d.backgroundImage != null) {
            Hint("背景色は背景画像の下に塗ります。画像の透過した部分から見えるので、大きさを画像の内側に合わせてください。")
        }
        PanelSection(d, update)
    }
}

/** 背景色の立体感 (埋め込み・浮き出し・ガラスの映り込み) */
@Composable
private fun PanelSection(d: GaugeDesign, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    Text("背景の立体感", fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        com.obdmeter.data.PanelStyle.entries.forEach { p ->
            FilterChip(d.panelStyle == p, { update { it.copy(panelStyle = p) } }, { Text(p.label) }, Modifier.padding(end = 6.dp))
        }
    }
    if (d.panelStyle != com.obdmeter.data.PanelStyle.FLAT) {
        SliderLine("立体感の強さ", d.panelDepth * 100, 0f..100f, "%.0f%%") { v -> update { it.copy(panelDepth = snap(v, 5f) / 100f) } }
    }
    SwitchLine("ガラスの映り込み", "上半分をうっすら明るくして、表示部にガラスがはまっているように見せます", d.panelGloss) { v ->
        update { it.copy(panelGloss = v) }
    }
    Hint("「埋め込み」は計器の液晶窓のように、上の縁から内側へ短い影を落とし、ふちを細く暗くして、表示部が文字盤の奥に沈んでいるように見せます。")
}

@Composable
private fun RingSection(d: GaugeDesign, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    Head("外周リング")
    Row {
        RingStyle.entries.forEach { r ->
            FilterChip(d.ring == r, { update { it.copy(ring = r) } }, { Text(r.label) }, Modifier.padding(end = 6.dp))
        }
    }
    if (d.ring != RingStyle.NONE) {
        ColorLine("リングの色", d.ringColor, GaugeColors.Ring) { c -> update { it.copy(ringColor = c) } }
        SliderLine("リングの太さ", d.ringThickness * 100, 30f..300f, "%.0f%%") { v ->
            update { it.copy(ringThickness = snap(v, 5f) / 100f) }
        }
        if (d.ring == RingStyle.METAL || d.ring == RingStyle.BOLD) {
            SliderLine("立体感 (陰影の強さ)", d.ringDepth * 100, 0f..100f, "%.0f%%") { v ->
                update { it.copy(ringDepth = snap(v, 5f) / 100f) }
            }
        }
        if (d.ring == RingStyle.METAL) {
            SliderLine("内側の段の幅 (凹んだ部分)", d.ringInner * 100, 0f..80f, "%.0f%%") { v ->
                update { it.copy(ringInner = snap(v, 5f) / 100f) }
            }
        }
    }
}

@Composable
private fun PeakSection(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    val d = g.design
    Head("ピーク値 (最大値)")
    SwitchLine(if (g.style == GaugeStyle.DIGITAL) "最大値を表示する" else "最大値の位置に印を付ける",
        "ダッシュボードでメーターを長押しするとリセットします", d.showPeak) { v ->
        update { it.copy(showPeak = v) }
    }
    if (d.showPeak) {
        if (g.style != GaugeStyle.DIGITAL) {
            SwitchLine("最大値を数字でも表示", null, d.showPeakValue) { v -> update { it.copy(showPeakValue = v) } }
            ColorLine("印の色", d.peakColor, GaugeColors.Peak) { c -> update { it.copy(peakColor = c) } }
        }
        if (d.showPeakValue || g.style == GaugeStyle.DIGITAL) {
            val default = d.peakColor?.let { Color(it) } ?: GaugeColors.Peak
            ColorLine(if (g.style == GaugeStyle.DIGITAL) "最大値の文字の色" else "最大値の文字の色 (既定 = 印の色)", d.peakTextColor, default) { c ->
                update { it.copy(peakTextColor = c) }
            }
            SliderLine("最大値の文字の大きさ", d.peakTextSize * 100, 30f..400f, "%.0f%%") { v -> update { it.copy(peakTextSize = snap(v, 5f) / 100f) } }
            SliderLine("最大値の文字の横位置 (右が +)", d.peakTextX * 100, -100f..100f, "%.0f") { v -> update { it.copy(peakTextX = snap(v, 1f) / 100f) } }
            SliderLine("最大値の文字の縦位置 (下が +)", d.peakTextY * 100, -100f..100f, "%.0f") { v -> update { it.copy(peakTextY = snap(v, 1f) / 100f) } }
        }
    }
}

/** 数値・名前・単位 (アナログ・バー・デジタル・ランプ) */
@Composable
private fun ValueNameUnitSection(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    val d = g.design
    val positions = g.style != GaugeStyle.LAMP
    Head("数値")
    SwitchLine("数値を表示", null, d.showValue) { v -> update { it.copy(showValue = v) } }
    if (d.showValue) {
        ColorLine("数値の色", d.valueColor, GaugeColors.Text) { c -> update { it.copy(valueColor = c) } }
        SliderLine("数値の大きさ", d.valueSize * 100, 30f..300f, "%.0f%%") { v -> update { it.copy(valueSize = snap(v, 5f) / 100f) } }
        if (positions) {
            SliderLine("数値の縦位置 (中心 = 0、下が +)", d.valuePosition * 100, -90f..90f, "%.0f") { v ->
                update { it.copy(valuePosition = snap(v, 1f) / 100f) }
            }
            SliderLine("数値の横位置 (中心 = 0、右が +)", d.valueOffsetX * 100, -90f..90f, "%.0f") { v ->
                update { it.copy(valueOffsetX = snap(v, 1f) / 100f) }
            }
        }
    }
    Head("名前・単位")
    ColorLine("文字の色 (名前・単位・目盛りの数字の既定)", d.labelColor, GaugeColors.SubText) { c -> update { it.copy(labelColor = c) } }
    val labelDefault = d.labelColor?.let { Color(it) } ?: GaugeColors.SubText
    SwitchLine("名前を表示", null, d.showName) { v -> update { it.copy(showName = v) } }
    if (d.showName) {
        ColorLine("名前の色 (既定 = 文字の色)", d.nameColor, labelDefault) { c -> update { it.copy(nameColor = c) } }
        SliderLine("名前の大きさ", d.tableNameSize * 100, 30f..300f, "%.0f%%") { v -> update { it.copy(tableNameSize = snap(v, 5f) / 100f) } }
        SliderLine("名前の横位置 (右が +)", d.nameOffsetX * 100, -60f..60f, "%.0f") { v ->
            update { it.copy(nameOffsetX = snap(v, 1f) / 100f) }
        }
        SliderLine("名前の縦位置 (下が +)", d.nameOffsetY * 100, -60f..60f, "%.0f") { v ->
            update { it.copy(nameOffsetY = snap(v, 1f) / 100f) }
        }
    }
    SwitchLine("単位を表示", null, d.showUnit) { v -> update { it.copy(showUnit = v) } }
    if (d.showUnit && positions) {
        ColorLine("単位の色 (既定 = 文字の色)", d.unitColor, labelDefault) { c -> update { it.copy(unitColor = c) } }
        SliderLine("単位の大きさ", d.tableUnitSize * 100, 30f..300f, "%.0f%%") { v -> update { it.copy(tableUnitSize = snap(v, 5f) / 100f) } }
        SliderLine("単位の横位置 (右が +)", d.unitOffsetX * 100, -60f..60f, "%.0f") { v ->
            update { it.copy(unitOffsetX = snap(v, 1f) / 100f) }
        }
        SliderLine("単位の縦位置 (下が +)", d.unitOffsetY * 100, -60f..60f, "%.0f") { v ->
            update { it.copy(unitOffsetY = snap(v, 1f) / 100f) }
        }
        Hint("単位の位置は数値の位置からのずれです (数値を動かすと一緒に動きます)")
    }
}

@Composable
private fun CardSwitch(d: GaugeDesign, update: ((GaugeDesign) -> GaugeDesign) -> Unit, style: GaugeStyle? = null) {
    Head("カード")
    SwitchLine("四角いカードの背景を表示", "丸い背景画像を使うときや、背景を透かしたいときは OFF にします", d.showCard) { v ->
        update { it.copy(showCard = v) }
    }
    Head("影")
    if (style == GaugeStyle.ANALOG) {
        SwitchLine("針に影を付ける", "針とノブが文字盤から少し浮いているように、右下に影を落とします", d.needleShadow) { v ->
            update { it.copy(needleShadow = v) }
        }
    }
    val shapeNote = when {
        style == GaugeStyle.ANALOG -> "カードを出していなければ、丸い文字盤 (背景色・リング・画像があるとき) の形に付けます"
        style == GaugeStyle.BAR || style == GaugeStyle.DIGITAL || style == GaugeStyle.LAMP -> "カードを出していなければ、背景色の形に付けます"
        else -> "カードを出しているときに、カードの形に付けます"
    }
    SwitchLine("メーター自体に影を付ける", shapeNote, d.objectShadow) { v -> update { it.copy(objectShadow = v) } }
    if (d.needleShadow || d.objectShadow) {
        SliderLine("影の濃さ", d.shadowStrength * 100, 0f..150f, "%.0f%%") { v -> update { it.copy(shadowStrength = snap(v, 5f) / 100f) } }
    }
}

// ---------------------------------------------------------------------------
// タブ: 点灯条件・色 (ランプ)
// ---------------------------------------------------------------------------

@Composable
private fun LampTab(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit, onPickFont: (String) -> Unit) {
    val d = g.design
    LampSettings(g, update)
    FontSection(d, onPickFont)
    FaceSection(d, update)
    RingSection(d, update)
    ValueNameUnitSection(g, update)
    CardSwitch(d, update, g.style)
    Head("背景画像")
    ImageTab(g, update)
}

// ---------------------------------------------------------------------------
// タブ: 並べ方・文字 (値テーブル)
// ---------------------------------------------------------------------------

@Composable
private fun TableTab(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit, onPickFont: (String) -> Unit) {
    val d = g.design
    TableLayoutSettings(g, update)
    FontSection(d, onPickFont)
    Head("文字の色")
    ColorLine("値の色", d.valueColor, GaugeColors.Text) { c -> update { it.copy(valueColor = c) } }
    ColorLine("文字の色 (項目名・単位の既定)", d.labelColor, GaugeColors.SubText) { c -> update { it.copy(labelColor = c) } }
    val labelDefault = d.labelColor?.let { Color(it) } ?: GaugeColors.SubText
    if (d.showName) ColorLine("項目名の色 (既定 = 文字の色)", d.nameColor, labelDefault) { c -> update { it.copy(nameColor = c) } }
    if (d.showUnit) ColorLine("単位の色 (既定 = 文字の色)", d.unitColor, labelDefault) { c -> update { it.copy(unitColor = c) } }
    GlowSection(g, update)
    CardSwitch(d, update)
}

// ---------------------------------------------------------------------------
// タブ: 表示 (折れ線グラフ・変動監視)
// ---------------------------------------------------------------------------

@Composable
private fun ChartViewTab(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit, onPickFont: (String) -> Unit) {
    val d = g.design
    Head("1 画面に表示する期間")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(
            600 to "10分", 1800 to "30分", 3600 to "1時間", 10800 to "3時間", 21600 to "6時間", 43200 to "12時間",
            86400 to "1日", 259200 to "3日", 604800 to "1週間", 2592000 to "1か月",
        ).forEach { (sec, label) ->
            FilterChip(d.chartSpanSec == sec, { update { it.copy(chartSpanSec = sec) } }, { Text(label) },
                Modifier.padding(end = 6.dp))
        }
    }
    Hint("記録の全期間を表示できます。左右にドラッグでスクロール、2 本指で期間を拡大縮小、ダブルタップで現在の表示に戻ります。")
    SwitchLine("記録の無い時間を詰めて表示", "どの項目にも記録が無い時間 (エンジン停止中など) を飛ばして、切れ目に点線を出します",
        d.chartSkipGaps) { v -> update { it.copy(chartSkipGaps = v) } }

    Head("凡例 (項目名と現在値)")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        com.obdmeter.data.LegendPosition.entries.forEach { p ->
            FilterChip(d.legendPosition == p, { update { it.copy(legendPosition = p) } }, { Text(p.label) },
                Modifier.padding(end = 6.dp))
        }
    }
    if (d.legendPosition != com.obdmeter.data.LegendPosition.HIDDEN) {
        if (!d.legendPosition.inside) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                com.obdmeter.data.LegendAlign.entries.forEach { a ->
                    FilterChip(d.legendAlign == a, { update { it.copy(legendAlign = a) } }, { Text(a.label) },
                        Modifier.padding(end = 6.dp))
                }
            }
        }
        SliderLine("大きさ", d.legendScale, 0.4f..3f, "×%.2f") { v -> update { it.copy(legendScale = snap(v, 0.05f)) } }
        if (d.legendPosition.inside) {
            SliderLine("背景の濃さ", d.legendBgAlpha, 0f..1f, "%.2f") { v -> update { it.copy(legendBgAlpha = snap(v, 0.05f)) } }
        }
        SwitchLine("単位を表示", null, d.showUnit) { v -> update { it.copy(showUnit = v) } }
        ColorLine("項目名の色", d.labelColor, GaugeColors.SubText) { c -> update { it.copy(labelColor = c) } }
        Hint("タップした時刻の日時と値もここに出ます。値は線の色で表示します。")
    }
    FontSection(d, onPickFont)
    GlowSection(g, update)
    CardSwitch(d, update)
}

@Composable
private fun MonitorViewTab(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit, onPickFont: (String) -> Unit) {
    val d = g.design
    Head("最初に表示する期間")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        com.obdmeter.data.Monitor.WINDOWS.forEach { (ms, label) ->
            FilterChip(com.obdmeter.data.Monitor.windowIndex(d.monitorWindowMs) == com.obdmeter.data.Monitor.windowIndex(ms),
                { update { it.copy(monitorWindowMs = ms) } }, { Text("直近$label") },
                Modifier.padding(end = 6.dp))
        }
    }
    Hint(
        "ダッシュボードでは、上の期間の欄をタップして切り替えられます。現在の値は「最新の計測値 − 初回の計測値」です。" +
            "値は項目の取得周期 (設定画面) ごとに見て、このページを表示していないときも記録します。" +
            "記録はこのメーターを削除するまで残ります (表示形式を変えた場合も消えます)。" +
            "メーター右上の「書出」「読込」で記録を CSV で書き出し・読み込み、「リセット」で記録を消せます。"
    )

    Head("文字")
    ColorLine("回数・選んだ期間の色 (= メーターの色)", d.color, GaugeColors.Accent) { c -> update { it.copy(color = c) } }
    ColorLine("現在の値の色", d.valueColor, GaugeColors.Text) { c -> update { it.copy(valueColor = c) } }
    ColorLine("項目名の色", d.labelColor, GaugeColors.SubText) { c -> update { it.copy(labelColor = c) } }
    SliderLine("回数・値の大きさ", d.valueSize * 100, 30f..300f, "%.0f%%") { v -> update { it.copy(valueSize = snap(v, 5f) / 100f) } }
    SliderLine("項目名の大きさ", d.labelSize * 100, 40f..250f, "%.0f%%") { v -> update { it.copy(labelSize = snap(v, 5f) / 100f) } }
    SwitchLine("単位を表示", null, d.showUnit) { v -> update { it.copy(showUnit = v) } }
    FontSection(d, onPickFont)
    GlowSection(g, update)
    CardSwitch(d, update)
}

// ---------------------------------------------------------------------------
// タブ: 画像 (アナログ・バー・デジタル。ランプは「点灯条件・色」タブの中)
// ---------------------------------------------------------------------------

@Composable
private fun ImageTab(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    val d = g.design
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val latestUpdate by rememberUpdatedState(update)
    val analog = g.style == GaugeStyle.ANALOG

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            runCatching { GaugeImages.import(context, uri) }
                .onSuccess { name ->
                    error = null
                    latestUpdate {
                        if (it.backgroundImage == null && analog) {
                            // 初めて画像を付けたときは、画像が映えるように枠や下地の弧を消し、目盛りを少し内側へ
                            it.copy(backgroundImage = name, showCard = false, ring = RingStyle.NONE,
                                showTrack = false, tickRadius = minOf(it.tickRadius, 0.85f))
                        } else {
                            it.copy(backgroundImage = name)
                        }
                    }
                }
                .onFailure { error = it.message ?: "画像を読み込めませんでした" }
            loading = false
        }
    }

    Hint(
        if (analog) "メーターの文字盤 (目盛りや数字のない画像) を背景に使えます。目盛り・数字・針は画像の上に描かれます。\n" +
            "透過 PNG も使えます。正方形に近い画像がおすすめです。"
        else "メーターの背景に画像を使えます。数値などは画像の上に描かれます。透過 PNG も使えます。"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        FilledTonalButton(
            enabled = !loading,
            onClick = {
                val ok = runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.isSuccess
                if (!ok) Toast.makeText(context, "この端末では画像を選ぶ画面を開けません", Toast.LENGTH_LONG).show()
            },
        ) { Text(if (loading) "読み込み中…" else if (d.backgroundImage == null) "画像を選ぶ" else "画像を変更") }
        if (d.backgroundImage != null) {
            OutlinedButton(onClick = { update { it.copy(backgroundImage = null) } }) { Text("画像を外す") }
        }
    }
    error?.let { Text("⚠ $it", color = GaugeColors.Warn, fontSize = 13.sp) }

    if (d.backgroundImage != null) {
        SliderLine("画像の大きさ", d.imageScale * 100, 30f..200f, "%.0f%%") { v -> update { it.copy(imageScale = snap(v, 1f) / 100f) } }
        SliderLine("画像の横位置 (右が +)", d.imageOffsetX * 100, -50f..50f, "%.0f%%") { v ->
            update { it.copy(imageOffsetX = snap(v, 1f) / 100f) }
        }
        SliderLine("画像の縦位置 (下が +)", d.imageOffsetY * 100, -50f..50f, "%.0f%%") { v ->
            update { it.copy(imageOffsetY = snap(v, 1f) / 100f) }
        }
        if (g.style != GaugeStyle.LAMP) {
            Head("画像に合わせる")
            if (analog) {
                Hint("目盛りが画像の枠と重なる場合は「目盛りの位置」を、数値を画像の表示窓に入れたい場合は「数値の縦位置・横位置」を調整してください。")
                SliderLine("目盛りの位置 (外周 = 100%)", d.tickRadius * 100, 40f..110f, "%.0f%%") { v ->
                    update { it.copy(tickRadius = snap(v, 1f) / 100f) }
                }
            }
            SliderLine("数値の縦位置 (中心 = 0、下が +)", d.valuePosition * 100, -90f..90f, "%.0f") { v ->
                update { it.copy(valuePosition = snap(v, 1f) / 100f) }
            }
            SliderLine("数値の横位置 (中心 = 0、右が +)", d.valueOffsetX * 100, -90f..90f, "%.0f") { v ->
                update { it.copy(valueOffsetX = snap(v, 1f) / 100f) }
            }
            SwitchLine("四角いカードの背景を表示", null, d.showCard) { v -> update { it.copy(showCard = v) } }
            if (g.style != GaugeStyle.DIGITAL) SwitchLine("下地の弧・バー", null, d.showTrack) { v -> update { it.copy(showTrack = v) } }
        }
    }
}

// ---------------------------------------------------------------------------
// 部品
// ---------------------------------------------------------------------------

private fun snap(v: Float, step: Float): Float = (v / step).roundToInt() * step

@Composable
private fun Head(text: String) {
    Text(text, color = GaugeColors.Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = GaugeColors.SubText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun SwitchLine(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (detail != null) Text(detail, color = GaugeColors.SubText, fontSize = 12.sp)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderLine(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
            Text(format.format(value), color = GaugeColors.SubText, fontSize = 13.sp)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

/** 2 値ランプの設定 (点灯条件と色) */
@Composable
private fun LampSettings(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    val d = g.design
    Head("点灯条件")
    Hint(
        "計測値を x として条件を書きます。例: x >= 100 / x > 90 && x < 110 / x < 11.5 || x > 14.8 / abs(x) > 5。" +
            "空欄のときは警告値 (「項目」タブ) を超えたら点灯します。"
    )
    var text by remember(g.id) { mutableStateOf(d.lampCondition) }
    val error = text.takeIf { it.isNotBlank() }?.let { Condition.check(it) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            // 書き方が正しいときだけ反映する (途中の入力でランプが消えないように)
            if (t.isBlank() || Condition.check(t) == null) update { it.copy(lampCondition = t.trim()) }
        },
        placeholder = { Text("例: x >= 100") },
        isError = error != null,
        supportingText = { if (error != null) Text(error) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    ColorLine("点灯時の色", d.lampOnColor, GaugeColors.Warn) { c -> update { it.copy(lampOnColor = c) } }
    ColorLine("消灯時の色", d.lampOffColor, Color(0xFF2A2F36)) { c -> update { it.copy(lampOffColor = c) } }
}

/** 値テーブルの並べ方・幅・値の背景 */
@Composable
private fun TableLayoutSettings(
    g: GaugeConfig,
    update: ((GaugeDesign) -> GaugeDesign) -> Unit,
) {
    val d = g.design
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    val latestUpdate by rememberUpdatedState(update)

    Head("並べ方")
    SliderLine("1 段の最大行数", d.tableMaxRows.toFloat(), 1f..15f, "%.0f 行") { v ->
        update { it.copy(tableMaxRows = v.roundToInt().coerceIn(1, 15)) }
    }
    SwitchLine("項目名を表示", "OFF にすると値と単位だけを並べます", d.showName) { v -> update { it.copy(showName = v) } }
    if (d.showName) {
        SwitchLine("項目名を値の枠の左上に表示", "ON にすると項目名の欄をなくして、値の枠の左上に小さく表示します", d.tableNameInline) { v ->
            update { it.copy(tableNameInline = v) }
        }
        if (!d.tableNameInline) {
            SliderLine("項目名の幅", d.tableNameRatio * 100, 0f..70f, "%.0f%%") { v -> update { it.copy(tableNameRatio = snap(v, 1f) / 100f) } }
        }
        SliderLine("項目名の文字の大きさ", d.tableNameSize * 100, 30f..300f, "%.0f%%") { v -> update { it.copy(tableNameSize = snap(v, 5f) / 100f) } }
    }
    SwitchLine("単位を表示", "OFF にすると単位の欄をなくして、値の部分を広げます", d.showUnit) { v -> update { it.copy(showUnit = v) } }
    if (d.showUnit) {
        SwitchLine("単位を値に付ける", "ON にすると単位の欄をなくして、値のすぐ後ろに単位を表示します", d.tableUnitInline) { v ->
            update { it.copy(tableUnitInline = v) }
        }
        if (!d.tableUnitInline) {
            SliderLine("単位の幅", d.tableUnitRatio * 100, 0f..50f, "%.0f%%") { v -> update { it.copy(tableUnitRatio = snap(v, 1f) / 100f) } }
        }
        SliderLine("単位の文字の大きさ", d.tableUnitSize * 100, 30f..300f, "%.0f%%") { v -> update { it.copy(tableUnitSize = snap(v, 5f) / 100f) } }
    }
    SliderLine("値の文字の大きさ", d.valueSize * 100, 30f..300f, "%.0f%%") { v -> update { it.copy(valueSize = snap(v, 5f) / 100f) } }
    SliderLine("各行の下の余白", d.tableMarginBottom * 100, 0f..60f, "%.0f%%") { v -> update { it.copy(tableMarginBottom = snap(v, 1f) / 100f) } }
    SliderLine("各段の右の余白", d.tableMarginRight * 100, 0f..60f, "%.0f%%") { v -> update { it.copy(tableMarginRight = snap(v, 1f) / 100f) } }
    SliderLine("値と値の間 (上下)", d.tableRowGap, -60f..80f, "%.0f dp") { v -> update { it.copy(tableRowGap = snap(v, 1f)) } }
    if (d.tableMaxRows < g.chartAllKeys.size) {
        SliderLine("段と段の間 (左右)", d.tableColGap, -60f..120f, "%.0f dp") { v -> update { it.copy(tableColGap = snap(v, 1f)) } }
    }
    Hint(
        "「値と値の間」は行と行・段と段の間だけに空け、外側には空けません。0 で枠どうしがすき間なく並びます。" +
            "マイナスにすると、文字の上下の空きを重ねてさらに詰め、そのぶん文字が大きくなります。" +
            "「余白」は各行・各段の内側に空けます (文字も小さくなります)。"
    )
    Hint("項目名は右寄せ、単位は左寄せで表示します。残りの幅が値の部分です。")

    Head("値の部分の背景")
    ColorLine("背景色 (既定 = なし)", d.tableValueBg, Color.Transparent) { c -> update { it.copy(tableValueBg = c) } }
    if (d.tableValueBg != null || d.tableValueImage != null) PanelSection(d, update)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            runCatching { GaugeImages.import(context, uri) }
                .onSuccess { name -> latestUpdate { it.copy(tableValueImage = name) } }
                .onFailure { Toast.makeText(context, it.message ?: "画像を読み込めませんでした", Toast.LENGTH_LONG).show() }
            loading = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        FilledTonalButton(enabled = !loading, onClick = {
            val ok = runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.isSuccess
            if (!ok) Toast.makeText(context, "この端末では画像を選ぶ画面を開けません", Toast.LENGTH_LONG).show()
        }) { Text(if (loading) "読み込み中…" else if (d.tableValueImage == null) "背景画像を選ぶ" else "背景画像を変更") }
        if (d.tableValueImage != null) {
            OutlinedButton(onClick = { update { it.copy(tableValueImage = null) } }) { Text("画像を外す") }
        }
    }
    Hint("背景画像は値の部分いっぱいに伸ばして表示します (背景色の上に重ねます)。")
}

/** 並べる項目の一覧 (名前の書き換え・並べ替え・外す・追加)。値テーブルと変動監視で使う */
@Composable
private fun ItemRows(
    g: GaugeConfig,
    catalog: PidCatalog,
    update: ((GaugeDesign) -> GaugeDesign) -> Unit,
    replace: (GaugeConfig) -> Unit,
    onAddItem: () -> Unit,
) {
    val d = g.design
    val keys = g.chartAllKeys
    fun reorder(from: Int, to: Int) {
        val list = keys.toMutableList()
        val k = list.removeAt(from); list.add(to, k)
        val newMain = list[0]
        // 先頭 (表示する項目) が変わったら、その項目用の範囲の上書きは外す。表示名は項目について移す
        if (newMain == g.pidKey) { replace(g.copy(chartKeys = list.drop(1))); return }
        var labels = g.itemLabels - newMain
        d.label?.takeIf { it.isNotBlank() }?.let { labels = labels + (g.pidKey to it) }
        val design = d.copy(label = g.itemLabels[newMain], min = null, max = null, decimals = null)
        replace(g.copy(pidKey = newMain, chartKeys = list.drop(1), design = design, itemLabels = labels))
    }
    Hint("名前の欄に入力すると、表に出す項目名を変えられます (空欄で標準の名前)。")
    keys.forEachIndexed { i, key ->
        val def = catalog.get(key)
        Text("${i + 1}. " + (def?.name ?: "(見つかりません: $key)"), fontSize = 13.sp, color = GaugeColors.SubText,
            modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 表示名 (先頭は design.label、2 番目以降は itemLabels)
            val current = if (i == 0) d.label.orEmpty() else g.itemLabels[key].orEmpty()
            var text by remember(g.id, key, i) { mutableStateOf(current) }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v
                    val t = v.takeIf { it.isNotBlank() }
                    if (i == 0) update { it.copy(label = t) }
                    else replace(g.copy(itemLabels = if (t == null) g.itemLabels - key else g.itemLabels + (key to t)))
                },
                placeholder = { Text(def?.shortName ?: "") },
                label = { Text("名前") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { reorder(i, i - 1) }, enabled = i > 0) { Text("▲") }
            TextButton(onClick = { reorder(i, i + 1) }, enabled = i < keys.lastIndex) { Text("▼") }
            if (i > 0) TextButton(onClick = {
                replace(g.copy(chartKeys = g.chartKeys.toMutableList().also { it.removeAt(i - 1) }))
            }) { Text("外す") }
        }
    }
    if (g.chartKeys.size < GaugeConfig.MAX_TABLE_EXTRA) {
        OutlinedButton(onClick = onAddItem) { Text("項目を追加") }
    }
}

/** 折れ線グラフの項目・縦軸・線の色 */
@Composable
private fun ChartItems(
    g: GaugeConfig,
    catalog: PidCatalog,
    update: ((GaugeDesign) -> GaugeDesign) -> Unit,
    replace: (GaugeConfig) -> Unit,
    onAddItem: () -> Unit,
) {
    val d = g.design
    val look = g.look(catalog.get(g.pidKey) ?: return)
    Head("グラフに表示する項目と縦軸")
    Hint("先頭は「表示する項目」です。縦軸は左右 2 つあり、項目ごとにどちらの軸に属するか選べます (既定は奇数番目が右、偶数番目が左)。各軸の範囲は、属する項目の目盛りの範囲 (最小〜最大) をまとめたものです。")
    g.chartAllKeys.forEachIndexed { i, key ->
        val def = catalog.get(key)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(chartColor(look, i)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(def?.name ?: "(見つかりません: $key)", fontSize = 14.sp)
                if (def != null) Text("${def.unit}  範囲 ${fmt(def.min)}〜${fmt(def.max)}", color = GaugeColors.SubText, fontSize = 12.sp)
            }
            // 左右どちらの縦軸か
            val right = g.isRightAxis(i, key)
            FilterChip(!right, { replace(g.copy(chartAxis = g.chartAxis + (key to false))) }, { Text("左") },
                Modifier.padding(end = 4.dp))
            FilterChip(right, { replace(g.copy(chartAxis = g.chartAxis + (key to true))) }, { Text("右") })
            if (i > 0) {
                TextButton(onClick = {
                    val keys = g.chartKeys.toMutableList().also { it.removeAt(i - 1) }
                    val colors = d.chartColors.toMutableList().also { if (i < it.size) it.removeAt(i) }
                    replace(g.copy(chartKeys = keys, design = d.copy(chartColors = colors)))
                }) { Text("外す") }
            }
        }
    }
    if (g.chartKeys.size < GaugeConfig.MAX_CHART_EXTRA) {
        OutlinedButton(onClick = onAddItem) { Text("項目を追加") }
    }

    Head("線の色")
    g.chartAllKeys.forEachIndexed { i, key ->
        val name = catalog.get(key)?.shortName ?: key
        ColorLine(name, d.chartColors.getOrNull(i), chartColor(look.copy(chartColors = emptyList()), i)) { c ->
            update {
                val list = it.chartColors.toMutableList()
                while (list.size <= i) list += null
                list[i] = c
                it.copy(chartColors = list)
            }
        }
    }
}

/** 折れ線グラフの記録の設定 */
@Composable
private fun ChartRecordTab(
    g: GaugeConfig,
    catalog: PidCatalog,
    update: ((GaugeDesign) -> GaugeDesign) -> Unit,
) {
    val d = g.design
    Head("記録するタイミング")
    SwitchLine("このページを表示している間だけ記録する",
        "ON にすると、ほかのページを表示している間はこのグラフの項目を記録しません (車との通信が減り、ほかのメーターの反応が良くなります)",
        d.recordOnlyWhenShown) { v -> update { it.copy(recordOnlyWhenShown = v) } }

    Head("記録する間隔")
    Hint("記録はグラフごとに別々です。同じ項目をほかのグラフでも使っていても、このグラフの設定で記録します (新しく置いたグラフは空から始まります)。")
    val interval = d.recordIntervalSec.toInt().coerceIn(HistoryStore.MIN_INTERVAL_SEC, HistoryStore.MAX_INTERVAL_SEC)
    SwitchLine("変化したときだけ記録する",
        "記録する間隔を使わず、項目の取得周期 (設定画面) ごとに値を見て、前回記録した値から変わったときだけ記録します。" +
            "記録は値が変わった時刻だけになり、グラフは次に変わるまで前の値が続く階段状の線で描きます",
        d.recordOnChange) { v -> update { it.copy(recordOnChange = v) } }
    if (d.recordOnChange) {
        Hint(
            "取得周期を設定していない項目は 0.2 秒ごとに値を見ます。このページを表示していない間に記録のために読む項目は、" +
                "1 秒に 1 回までにします。"
        )
    } else {
        SliderLine("間隔 (秒)", interval.toFloat(), HistoryStore.MIN_INTERVAL_SEC.toFloat()..HistoryStore.MAX_INTERVAL_SEC.toFloat(),
            "%.0f 秒") { v -> update { it.copy(recordIntervalSec = (if (v < 60f) snap(v, 1f) else snap(v, 10f)).coerceAtLeast(1f)) } }
        Hint(
            "接続した直後にすべての項目を 1 回記録し、その後はこの間隔で記録します " +
                "(グラフのページを表示していなくても記録します)。"
        )
    }

    Head("記録を残す期間")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(1 to "1時間", 24 to "1日", 168 to "1週間").forEach { (h, label) ->
            FilterChip(d.historyHours == h, { update { it.copy(historyHours = h) } }, { Text(label) },
                Modifier.padding(end = 6.dp))
        }
        listOf(1, 2, 3, 6, 12, 24).forEach { n ->
            FilterChip(d.historyHours <= 0 && d.historyMonths == n, { update { it.copy(historyHours = 0, historyMonths = n) } },
                { Text("${n}か月") }, Modifier.padding(end = 6.dp))
        }
    }
    val days = d.historyRetentionMs / 86_400_000f
    val mb = days * 86_400f / interval * g.chartAllKeys.size * 40 / 1_000_000f
    Hint(
        "期間を過ぎた記録は自動で消えます (複数のグラフがあるときは一番長い期間)。" +
            if (d.recordOnChange) "変化したときだけ記録するので、使う容量は値の変わり方しだいです。"
            else "この設定で最大約 ${"%.1f".format(mb)} MB を使います。"
    )

    Head("記録の CSV")
    Hint(
        "ダッシュボードのグラフ内の「書出」「読込」ボタンで、このグラフの項目の記録を CSV で書き出し・読み込みできます。" +
            "形式は 1 行に「項目キー, 年月日時分秒, 値」(例: COOLANT,2026-10-18 00:46:00,88)。" +
            "読み込みでは、同じ項目・同じ時刻の記録は上書きされます。"
    )
    Text(
        g.chartAllKeys.joinToString("  ") { k -> "${catalog.get(k)?.shortName ?: k} = $k" },
        color = GaugeColors.SubText, fontSize = 12.sp,
    )
}

/** 色の選択。null = 既定色 */
@Composable
private fun ColorLine(
    label: String,
    value: Long?,
    default: Color,
    extra: List<Pair<String, Long>> = emptyList(),
    onChange: (Long?) -> Unit,
) {
    val presets = Layout.COLORS + EXTRA_COLORS + extra
    var editing by remember { mutableStateOf(false) }
    Text(label, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        ColorDot(default, value == null, "既定") { onChange(null) }
        Spacer(Modifier.width(4.dp))
        // RGB で指定した色 (候補に無い色) は先頭に出す。タップで RGB の画面を開く
        if (value != null && presets.none { it.second == value }) {
            ColorDot(Color(value), true, "指定した色") { editing = true }
        }
        Box(
            Modifier
                .padding(end = 8.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, GaugeColors.CardBorder, RoundedCornerShape(14.dp))
                .clickable { editing = true }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text("RGB", fontSize = 12.sp) }
        presets.forEach { (name, argb) ->
            ColorDot(Color(argb), value == argb, name) { onChange(argb) }
        }
    }
    if (editing) {
        RgbColorDialog(
            title = label,
            initial = value ?: (default.toArgb().toLong() and 0xFFFFFFFFL),
            onDismiss = { editing = false },
            onPick = { onChange(it); editing = false },
        )
    }
}

/** 色を R・G・B (0〜255) と不透明度、またはカラーコード (#RRGGBB / #AARRGGBB) で指定する */
@Composable
private fun RgbColorDialog(title: String, initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var a by remember { mutableStateOf(((initial shr 24) and 0xFF).toInt()) }
    var r by remember { mutableStateOf(((initial shr 16) and 0xFF).toInt()) }
    var g by remember { mutableStateOf(((initial shr 8) and 0xFF).toInt()) }
    var b by remember { mutableStateOf((initial and 0xFF).toInt()) }
    fun argb(): Long = (a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    fun hexOf(): String = if (a == 255) "%02X%02X%02X".format(r, g, b) else "%02X%02X%02X%02X".format(a, r, g, b)
    var hex by remember { mutableStateOf(hexOf()) }
    var hexError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 見本 (暗い背景と明るい背景の両方に置いて見え方を確かめる)
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    Box(Modifier.weight(1f).fillMaxSize().background(GaugeColors.Background).padding(6.dp)) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(argb())))
                    }
                    Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFFE8EAED)).padding(6.dp)) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(argb())))
                    }
                }
                Spacer(Modifier.height(8.dp))
                RgbSlider("R", r, Color(0xFFFF4040)) { r = it; hex = hexOf(); hexError = false }
                RgbSlider("G", g, Color(0xFF40C040)) { g = it; hex = hexOf(); hexError = false }
                RgbSlider("B", b, Color(0xFF4080FF)) { b = it; hex = hexOf(); hexError = false }
                RgbSlider("不透明", a, GaugeColors.SubText) { a = it; hex = hexOf(); hexError = false }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { t ->
                        hex = t.uppercase().filter { it in "0123456789ABCDEF#" }.take(9)
                        val h = hex.removePrefix("#")
                        val v = h.toLongOrNull(16)
                        hexError = v == null || (h.length != 6 && h.length != 8)
                        if (!hexError && v != null) {
                            a = if (h.length == 8) ((v shr 24) and 0xFF).toInt() else 255
                            r = ((v shr 16) and 0xFF).toInt()
                            g = ((v shr 8) and 0xFF).toInt()
                            b = (v and 0xFF).toInt()
                        }
                    },
                    label = { Text("カラーコード (RRGGBB / AARRGGBB)") },
                    prefix = { Text("#") },
                    isError = hexError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(argb()) }) { Text("決定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun RgbSlider(label: String, value: Int, color: Color, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(44.dp), fontSize = 13.sp)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
            modifier = Modifier.weight(1f),
        )
        Text(value.toString(), Modifier.width(36.dp), fontSize = 13.sp, color = GaugeColors.SubText)
    }
}

private val EXTRA_COLORS: List<Pair<String, Long>> = listOf(
    "黒" to 0xFF000000,
    "濃いグレー" to 0xFF3A3F47,
    "グレー" to 0xFF8A93A0,
    "シルバー" to 0xFFC8CDD3,
    "赤みのオレンジ" to 0xFFFF5A36,
    "半透明の赤" to 0x66FF5A36L,
)


@Composable
private fun FontLine(label: String, current: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(current, color = GaugeColors.SubText, fontSize = 12.sp)
        }
        OutlinedButton(onClick = onClick) { Text("変更") }
    }
}

/**
 * フォントの選択。ライブラリのフォントを、そのフォントで描いた見本付きで一覧する。
 * 「フォントを追加」で .ttf / .otf を取り込むと、ライブラリに入り、以後どのメーターでも選べる。
 */
@Composable
private fun FontPickerDialog(
    vm: MainViewModel,
    title: String,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val fonts by vm.fonts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<FontEntry?>(null) }
    var deleting by remember { mutableStateOf<FontEntry?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        vm.importFont(uri) { r ->
            busy = false
            r.onSuccess { entry -> error = null; onPick(entry.id) }
                .onFailure { error = it.message ?: "読み込めませんでした" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FontRow("既定", null, selected == null, sample = "0123 回転数") { onPick(null) }
                fonts.forEach { f ->
                    val family = remember(f.id) { GaugeFonts.family(context, f) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            FontRow(f.name, family, selected == f.id, sample = "0123 回転数") { onPick(f.id) }
                        }
                        IconButton(onClick = { renaming = f }) { Icon(Icons.Default.Edit, "名前変更") }
                        IconButton(onClick = { deleting = f }) { Icon(Icons.Default.Delete, "削除") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    enabled = !busy,
                    onClick = {
                        val ok = runCatching {
                            picker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf",
                                "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype",
                                "application/octet-stream", "*/*"))
                        }.isSuccess
                        if (!ok) Toast.makeText(context, "この端末ではファイルを選ぶ画面を開けません", Toast.LENGTH_LONG).show()
                    },
                ) { Text(if (busy) "読み込み中…" else "フォントを追加 (.ttf / .otf)") }
                error?.let { Text("⚠ $it", color = GaugeColors.Warn, fontSize = 13.sp) }
                Hint("日本語を表示する場合は、日本語を含むフォントを選んでください。含まれない文字は既定のフォントで表示されます。")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )

    renaming?.let { f ->
        var name by remember(f.id) { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("フォント名を変更") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { vm.renameFont(f.id, name.trim().ifEmpty { f.name }); renaming = null }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("キャンセル") } },
        )
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("「${f.name}」を削除しますか？") },
            text = { Text("このフォントを使っているメーターは既定のフォントに戻ります。") },
            confirmButton = {
                TextButton(onClick = {
                    if (selected == f.id) onPick(null)
                    vm.deleteFont(f.id)
                    deleting = null
                }) { Text("削除", color = GaugeColors.Warn) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun FontRow(name: String, family: FontFamily?, selected: Boolean, sample: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(name, fontSize = 13.sp, color = GaugeColors.SubText)
            Text(sample, fontSize = 22.sp, fontFamily = family)
        }
    }
}

// ---------------------------------------------------------------------------
// ページの背景 (色・柄・画像)
// ---------------------------------------------------------------------------

/**
 * ページの背景の編集 (全画面)。上 (横画面では左) にプレビュー、下に設定。変更は少し遅れて保存する
 */
@Composable
internal fun PageBackgroundDialog(
    vm: MainViewModel,
    dashboard: com.obdmeter.data.Dashboard,
    onDismiss: () -> Unit,
) {
    var bg by remember(dashboard.id) { mutableStateOf(dashboard.background) }
    val latest by rememberUpdatedState(bg)
    LaunchedEffect(bg) {
        if (bg != dashboard.background) {
            delay(250)
            vm.setPageBackground(dashboard.id, bg)
        }
    }
    val close = {
        if (latest != dashboard.background) vm.setPageBackground(dashboard.id, latest)
        onDismiss()
    }
    val cellAspect by vm.cellAspect.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            // ページ全体に使うので、メーターの画像より大きめ (長辺 2048) で取り込む
            runCatching { GaugeImages.import(context, uri, maxSize = 2048) }
                .onSuccess { name -> bg = bg.copy(image = name) }
                .onFailure { Toast.makeText(context, it.message ?: "画像を読み込めませんでした", Toast.LENGTH_LONG).show() }
            loading = false
        }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(GaugeColors.Background).safeDrawingPadding()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth > maxHeight
                val previewH = maxHeight * 0.32f
                // ページの縦横比 (マスの縦横比 × 24 / 12) でプレビューする
                val pageRatio = (cellAspect * Layout.COLS / Layout.ROWS).coerceIn(0.3f, 4f)
                val preview: @Composable (Modifier) -> Unit = { m ->
                    Box(m.padding(12.dp), contentAlignment = Alignment.Center) {
                        com.obdmeter.ui.gauges.PageBackgroundView(
                            bg,
                            Modifier.aspectRatio(pageRatio).border(1.dp, GaugeColors.CardBorder),
                        )
                    }
                }
                val settings: @Composable (Modifier) -> Unit = { m ->
                    Column(m.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        Hint("ページのいちばん後ろに、下から 色 → 柄 → 画像 の順に重ねます。メーターは必ずこの上に表示されます。")

                        Head("色")
                        ColorLine("背景色 (RGB でも指定できます)", bg.color, GaugeColors.Background) { c -> bg = bg.copy(color = c) }

                        Head("柄")
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            com.obdmeter.data.BgPattern.entries.forEach { p ->
                                FilterChip(bg.pattern == p, { bg = bg.copy(pattern = p) }, { Text(p.label) }, Modifier.padding(end = 6.dp))
                            }
                        }
                        if (bg.pattern != com.obdmeter.data.BgPattern.NONE) {
                            val base = bg.color?.let { Color(it) } ?: GaugeColors.Background
                            ColorLine("柄の色 (既定 = 背景色に合わせて白か黒)", bg.patternColor,
                                com.obdmeter.ui.gauges.autoPatternColor(base)) { c -> bg = bg.copy(patternColor = c) }
                            SliderLine("柄の大きさ", bg.patternScale * 100, 20f..600f, "%.0f%%") { v -> bg = bg.copy(patternScale = snap(v, 5f) / 100f) }
                            SliderLine("柄の濃さ", bg.patternAlpha * 100, 0f..100f, "%.0f%%") { v -> bg = bg.copy(patternAlpha = snap(v, 1f) / 100f) }
                            if (bg.pattern == com.obdmeter.data.BgPattern.WOOD) Hint("木目は、背景色を茶色系にして柄の色を暗い茶色にすると木らしくなります。")
                            if (bg.pattern == com.obdmeter.data.BgPattern.CARBON) Hint("カーボンは、黒に近い背景色に灰色の柄を 30〜60% くらいで重ねるとそれらしくなります。")
                        }

                        Head("画像")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            FilledTonalButton(enabled = !loading, onClick = {
                                val ok = runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.isSuccess
                                if (!ok) Toast.makeText(context, "この端末では画像を選ぶ画面を開けません", Toast.LENGTH_LONG).show()
                            }) { Text(if (loading) "読み込み中…" else if (bg.image == null) "画像を選ぶ" else "画像を変更") }
                            if (bg.image != null) OutlinedButton(onClick = { bg = bg.copy(image = null) }) { Text("画像を外す") }
                        }
                        if (bg.image != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    bg = bg.copy(imageX = 0.5f, imageY = 0.5f, imageWidth = 1f, imageHeight = 1f, keepAspect = false)
                                }) { Text("ページいっぱいに伸ばす") }
                                OutlinedButton(onClick = { bg = bg.copy(imageX = 0.5f, imageY = 0.5f) }) { Text("中央に置く") }
                            }
                            SliderLine("幅 (ページの幅 = 100%)", bg.imageWidth * 100, 5f..300f, "%.0f%%") { v -> bg = bg.copy(imageWidth = snap(v, 1f) / 100f) }
                            SwitchLine("縦横比を保つ", "OFF にすると高さを別に決められます (画像が伸び縮みします)", bg.keepAspect) { v -> bg = bg.copy(keepAspect = v) }
                            if (!bg.keepAspect) {
                                SliderLine("高さ (ページの高さ = 100%)", bg.imageHeight * 100, 5f..300f, "%.0f%%") { v -> bg = bg.copy(imageHeight = snap(v, 1f) / 100f) }
                            }
                            SliderLine("横の位置 (中心。左端 = 0%、右端 = 100%)", bg.imageX * 100, -50f..150f, "%.0f%%") { v -> bg = bg.copy(imageX = snap(v, 1f) / 100f) }
                            SliderLine("縦の位置 (中心。上端 = 0%、下端 = 100%)", bg.imageY * 100, -50f..150f, "%.0f%%") { v -> bg = bg.copy(imageY = snap(v, 1f) / 100f) }
                            SliderLine("不透明度", bg.imageAlpha * 100, 0f..100f, "%.0f%%") { v -> bg = bg.copy(imageAlpha = snap(v, 1f) / 100f) }
                        }

                        Head("ほかのページ")
                        OutlinedButton(onClick = { confirmAll = true }) { Text("すべてのページにこの背景を使う") }
                        Spacer(Modifier.height(24.dp))
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = close) { Icon(Icons.Default.Close, "閉じる") }
                        Text("「${dashboard.name}」の背景", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = close, modifier = Modifier.padding(end = 8.dp)) { Text("完了") }
                    }
                    if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            preview(Modifier.weight(1f).fillMaxHeight())
                            settings(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        preview(Modifier.fillMaxWidth().height(previewH))
                        settings(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
        if (confirmAll) {
            AlertDialog(
                onDismissRequest = { confirmAll = false },
                title = { Text("すべてのページにこの背景を使いますか？") },
                text = { Text("ほかのページの背景は、この背景に置き換わります。") },
                confirmButton = {
                    TextButton(onClick = { vm.applyBackgroundToAll(bg); confirmAll = false }) { Text("使う") }
                },
                dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("キャンセル") } },
            )
        }
    }
}
