package com.obdmeter.ui

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
    onChange: (GaugeConfig) -> Unit,
    onBringToFront: () -> Unit,
    onDelete: () -> Unit,
    onCreateCustomPid: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(gauge.id) { mutableStateOf(gauge) }
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

    var tab by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    var showFallbackPicker by remember { mutableStateOf(false) }
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
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            // メーターの縦横比 (マス目の比率) に合わせてプレビューする
                            val ratio = (draft.w.coerceAtLeast(1) * 2f / draft.h.coerceAtLeast(1)).coerceIn(0.3f, 4f)
                            if (def != null) {
                                GaugeCard(
                                    def = def,
                                    value = previewValue,
                                    style = draft.style,
                                    unsupported = false,
                                    look = draft.look(def),
                                    modifier = Modifier.aspectRatio(ratio).fillMaxHeight(),
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
                val settings: @Composable (Modifier) -> Unit = { m ->
                    Column(m) {
                        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                            listOf("基本", "角度・目盛り", "色・針・数値", "背景画像").forEachIndexed { i, t ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                            }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            when (tab) {
                                0 -> BasicTab(draft, catalog, ::update, { draft = it }, { showPicker = true },
                                    { showFallbackPicker = true }, onBringToFront) { confirmDelete = true }
                                1 -> AngleTab(draft, ::update)
                                2 -> ColorTab(draft, ::update) { target -> fontPickerFor = target }
                                3 -> ImageTab(draft, ::update)
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
// タブ: 基本
// ---------------------------------------------------------------------------

@Composable
private fun BasicTab(
    g: GaugeConfig,
    catalog: PidCatalog,
    update: ((GaugeDesign) -> GaugeDesign) -> Unit,
    replace: (GaugeConfig) -> Unit,
    onChangeItem: () -> Unit,
    onPickFallback: () -> Unit,
    onBringToFront: () -> Unit,
    onDelete: () -> Unit,
) {
    val base = catalog.get(g.pidKey)
    val d = g.design
    Head("表示する項目")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(base?.name ?: "(見つかりません: ${g.pidKey})", fontWeight = FontWeight.Medium)
            if (base != null) Text("${base.sourceLabel()}  ${base.unit}", color = GaugeColors.SubText, fontSize = 12.sp)
        }
        OutlinedButton(onClick = onChangeItem) { Text("変更") }
    }

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

    Head("表示形式")
    Row {
        GaugeStyle.entries.forEach { s ->
            FilterChip(g.style == s, { replace(g.copy(style = s)) }, { Text(s.label) }, Modifier.padding(end = 6.dp))
        }
    }

    Head("表示名 (空欄で「${base?.shortName ?: ""}」)")
    var labelText by remember(g.id, g.pidKey) { mutableStateOf(d.label.orEmpty()) }
    OutlinedTextField(
        value = labelText,
        onValueChange = { labelText = it; update { x -> x.copy(label = it.ifBlank { null }) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Head("目盛りの範囲 (空欄で既定値)")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("最小", d.min, base?.min, g.id to g.pidKey, Modifier.weight(1f)) { v -> update { it.copy(min = v) } }
        NumberField("最大", d.max, base?.max, g.id to g.pidKey, Modifier.weight(1f)) { v -> update { it.copy(max = v) } }
    }

    Head("小数点以下の桁数")
    Row {
        listOf<Int?>(null, 0, 1, 2, 3).forEach { n ->
            FilterChip(d.decimals == n, { update { it.copy(decimals = n) } }, { Text(n?.toString() ?: "既定") },
                Modifier.padding(end = 4.dp))
        }
    }

    SwitchLine("既定の警告値を使う",
        listOfNotNull(base?.warnAbove?.let { "上限 ${fmt(it)}" }, base?.warnBelow?.let { "下限 ${fmt(it)}" })
            .joinToString(" / ").ifEmpty { "警告なし" },
        d.useDefaultWarn) { v -> update { it.copy(useDefaultWarn = v) } }
    if (!d.useDefaultWarn) {
        Hint("値がこの範囲を超えると針・数値が赤くなります (空欄 = 警告なし)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("下限", d.warnBelow, null, g.id to g.pidKey, Modifier.weight(1f)) { v -> update { it.copy(warnBelow = v) } }
            NumberField("上限", d.warnAbove, null, g.id to g.pidKey, Modifier.weight(1f)) { v -> update { it.copy(warnAbove = v) } }
        }
    }

    Head("配置")
    Text("位置 (${g.x}, ${g.y})  大きさ ${g.w} × ${g.h} マス (全体 ${Layout.COLS} × ${Layout.ROWS})",
        color = GaugeColors.SubText, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = onBringToFront) { Text("最前面へ") }
        OutlinedButton(onClick = onDelete) { Text("削除", color = GaugeColors.Warn) }
    }
}

// ---------------------------------------------------------------------------
// タブ: 角度・目盛り・レッドゾーン
// ---------------------------------------------------------------------------

@Composable
private fun AngleTab(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    val d = g.design
    if (g.style != GaugeStyle.ANALOG) Hint("角度・目盛りはアナログ表示のときに使われます。レッドゾーンはバー表示でも使われます。")

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

    Head("目盛り")
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

    Head("弧")
    SwitchLine("下地の弧", null, d.showTrack) { v -> update { it.copy(showTrack = v) } }
    SwitchLine("現在値の弧 / バー", null, d.showValueArc) { v -> update { it.copy(showValueArc = v) } }
    SliderLine("弧・バーの太さ", d.arcWidth * 100, 20f..300f, "%.0f%%") { v -> update { it.copy(arcWidth = snap(v, 5f) / 100f) } }

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
// タブ: 色・リング・針・数値
// ---------------------------------------------------------------------------

@Composable
private fun ColorTab(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit, onPickFont: (String) -> Unit) {
    val d = g.design
    val lib = LocalFontLibrary.current

    Head("フォント")
    Hint("取り込んだフォントは、ほかのメーターでもそのまま選べます")
    FontLine("数値 (現在値・最大値)", d.valueFont?.let { lib[it]?.name ?: "(削除されたフォント)" } ?: "既定 (等幅)") { onPickFont("value") }
    FontLine("文字 (目盛りの数字・名前・単位)", d.labelFont?.let { lib[it]?.name ?: "(削除されたフォント)" } ?: "既定") { onPickFont("label") }

    ColorLine("メーターの色 (現在値の弧・バー)", d.color, GaugeColors.Accent) { c -> update { it.copy(color = c) } }

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
    }

    ColorLine("目盛りの色", d.tickColor, GaugeColors.Tick) { c -> update { it.copy(tickColor = c) } }
    ColorLine("文字の色 (目盛りの数字・名前・単位)", d.labelColor, GaugeColors.SubText) { c -> update { it.copy(labelColor = c) } }

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

    Head("ピーク値")
    SwitchLine("最大値の位置に印を付ける", "ダッシュボードでメーターを長押しするとリセットします", d.showPeak) { v ->
        update { it.copy(showPeak = v) }
    }
    if (d.showPeak) {
        SwitchLine("最大値を数字でも表示", null, d.showPeakValue) { v -> update { it.copy(showPeakValue = v) } }
        ColorLine("印の色", d.peakColor, GaugeColors.Peak) { c -> update { it.copy(peakColor = c) } }
    }

    Head("数値・名前")
    SwitchLine("数値を表示", null, d.showValue) { v -> update { it.copy(showValue = v) } }
    if (d.showValue) {
        ColorLine("数値の色", d.valueColor, GaugeColors.Text) { c -> update { it.copy(valueColor = c) } }
        SliderLine("数値の縦位置 (中心 = 0、下が +)", d.valuePosition * 100, -90f..90f, "%.0f") { v ->
            update { it.copy(valuePosition = snap(v, 1f) / 100f) }
        }
        SliderLine("数値の大きさ", d.valueSize * 100, 30f..300f, "%.0f%%") { v -> update { it.copy(valueSize = snap(v, 5f) / 100f) } }
    }
    SwitchLine("名前を表示", null, d.showName) { v -> update { it.copy(showName = v) } }
    SwitchLine("単位を表示", null, d.showUnit) { v -> update { it.copy(showUnit = v) } }
    SwitchLine("四角いカードの背景を表示", "丸い背景画像を使うときは OFF にするときれいです", d.showCard) { v ->
        update { it.copy(showCard = v) }
    }
}

// ---------------------------------------------------------------------------
// タブ: 背景画像
// ---------------------------------------------------------------------------

@Composable
private fun ImageTab(g: GaugeConfig, update: ((GaugeDesign) -> GaugeDesign) -> Unit) {
    val d = g.design
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val latestUpdate by rememberUpdatedState(update)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            runCatching { GaugeImages.import(context, uri) }
                .onSuccess { name ->
                    error = null
                    latestUpdate {
                        if (it.backgroundImage == null) {
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
        "メーターの文字盤 (目盛りや数字のない画像) を背景に使えます。目盛り・数字・針は画像の上に描かれます。\n" +
            "透過 PNG も使えます。正方形に近い画像がおすすめです。"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        FilledTonalButton(
            enabled = !loading,
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        ) { Text(if (loading) "読み込み中…" else if (d.backgroundImage == null) "画像を選ぶ" else "画像を変更") }
        if (d.backgroundImage != null) {
            OutlinedButton(onClick = { update { it.copy(backgroundImage = null) } }) { Text("画像を外す") }
        }
    }
    error?.let { Text("⚠ $it", color = GaugeColors.Warn, fontSize = 13.sp) }

    if (d.backgroundImage != null) {
        SliderLine("画像の大きさ", d.imageScale * 100, 30f..200f, "%.0f%%") { v -> update { it.copy(imageScale = snap(v, 1f) / 100f) } }
        Head("画像に合わせる")
        Hint("目盛りが画像の枠と重なる場合は「目盛りの位置」を、数値を画像の表示窓に入れたい場合は「数値の縦位置」を調整してください。")
        SliderLine("目盛りの位置 (外周 = 100%)", d.tickRadius * 100, 40f..110f, "%.0f%%") { v ->
            update { it.copy(tickRadius = snap(v, 1f) / 100f) }
        }
        SliderLine("数値の縦位置 (中心 = 0、下が +)", d.valuePosition * 100, -90f..90f, "%.0f") { v ->
            update { it.copy(valuePosition = snap(v, 1f) / 100f) }
        }
        SwitchLine("四角いカードの背景を表示", null, d.showCard) { v -> update { it.copy(showCard = v) } }
        SwitchLine("下地の弧", null, d.showTrack) { v -> update { it.copy(showTrack = v) } }
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

/** 色の選択。null = 既定色 */
@Composable
private fun ColorLine(
    label: String,
    value: Long?,
    default: Color,
    extra: List<Pair<String, Long>> = emptyList(),
    onChange: (Long?) -> Unit,
) {
    Text(label, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        ColorDot(default, value == null, "既定") { onChange(null) }
        Spacer(Modifier.width(4.dp))
        (Layout.COLORS + EXTRA_COLORS + extra).forEach { (name, argb) ->
            ColorDot(Color(argb), value == argb, name) { onChange(argb) }
        }
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
                        picker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf",
                            "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype",
                            "application/octet-stream", "*/*"))
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
