package com.obdmeter.ui

import com.obdmeter.util.format

import androidx.compose.ui.zIndex
import com.obdmeter.ui.gauges.PageBackgroundView
import com.obdmeter.data.HistorySlot
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import com.obdmeter.ui.gauges.ChartSeries
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdmeter.core.PidCatalog
import com.obdmeter.core.PidDef
import com.obdmeter.core.Pids
import com.obdmeter.data.ConnectionType
import com.obdmeter.data.Dashboard
import com.obdmeter.data.GaugeConfig
import com.obdmeter.data.GaugeStyle
import com.obdmeter.data.Layout
import com.obdmeter.session.ConnectionState
import com.obdmeter.ui.gauges.GaugeCard
import com.obdmeter.ui.gauges.GaugeColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(vm: MainViewModel, onNavigate: (Screen) -> Unit, onCreateCustomPid: () -> Unit) {
    val dashboards by vm.dashboards.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val values by vm.values.collectAsStateWithLifecycle()
    val rate by vm.rate.collectAsStateWithLifecycle()
    val supported by vm.supported.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()

    // 最後に表示していたページから始める (先頭ページが一瞬見えないよう、最初からそのページで作る)
    val pager = rememberPagerState(initialPage = remember { vm.initialPageIndex() }) { dashboards.size }
    val scope = rememberCoroutineScope()

    /** レイアウト編集モード (メーターをドラッグで移動・サイズ変更) */
    var editing by rememberSaveable { mutableStateOf(false) }
    val clipboard by vm.clipboard.collectAsStateWithLifecycle()
    val toast = rememberToast()
    /** 設定ダイアログを開いているメーターの id */
    var settingsFor by remember { mutableStateOf<Long?>(null) }
    /** メーター追加の項目選択ダイアログ (null = 閉じる) */
    var pickerFor by remember { mutableStateOf<Long?>(null) }
    var arrangeMenu by remember { mutableStateOf(false) }
    var gridMenu by remember { mutableStateOf(false) }
    /** グループにするメーターを選んでいる (編集中) */
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }

    val current = dashboards.getOrNull(pager.currentPage)
    // ページを変えた・編集をやめたら選択をやめる
    LaunchedEffect(current?.id, editing) { selecting = false; selected = emptySet() }

    // 表示中のページの項目だけを読み出す
    // iDrive・方向キーでのページ切替 (最後のページの次は先頭に戻る)
    DisposableEffect(editing) {
        vm.dashboardKeyNav = !editing
        onDispose { vm.dashboardKeyNav = false }
    }
    LaunchedEffect(Unit) {
        vm.pageNav.collect { d ->
            val n = pager.pageCount
            if (n > 1) pager.animateScrollToPage((pager.currentPage + d).mod(n))
        }
    }

    // 最後に表示していたページから始める (戻すまでは表示中のページを記録しない)
    var pageRestored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.pageToRestore()?.let { id ->
            val idx = vm.dashboards.value.indexOfFirst { it.id == id }
            if (idx >= 0 && idx != pager.currentPage) {
                // 画面側のページ数が追いつくのを待ってから移動する
                withTimeoutOrNull(2000) { snapshotFlow { pager.pageCount }.first { it > idx } }
                pager.scrollToPage(idx)
            }
        }
        pageRestored = true
    }
    LaunchedEffect(current?.id, pageRestored) {
        if (pageRestored) vm.setActivePage(current?.id)
    }

    // ---- 上部バー (接続状態・接続ボタン・編集・ログ・設定) ----
    val topBar: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editing && selecting) {
                Text("グループにするメーターをタップして選んでください (グループのメーターはまとめて選ばれます)",
                    color = GaugeColors.Peak, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else if (editing) {
                Text("レイアウト編集中: ドラッグで移動、右下の ◢ でサイズ変更、タップで設定、右上の ▲▼ で重なり順、長押しでコピー (グループはまとめて)",
                    color = GaugeColors.Accent, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box {
                    OutlinedButton(onClick = { gridMenu = true }) {
                        Text("グリッド " + (Layout.GRID_STEPS.firstOrNull { it.first == settings.gridStep }?.second ?: ""))
                    }
                    DropdownMenu(expanded = gridMenu, onDismissRequest = { gridMenu = false }) {
                        Layout.GRID_STEPS.forEach { (step, label) ->
                            DropdownMenuItem(
                                text = { Text((if (step == settings.gridStep) "✓ " else "    ") + label + "ごとに吸着") },
                                onClick = { vm.updateSettings { it.copy(gridStep = step) }; gridMenu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { editing = false }) {
                    Icon(Icons.Default.Check, null); Spacer(Modifier.width(4.dp)); Text("完了")
                }
            } else {
                StatusIndicator(connection, rate, Modifier.weight(1f))
                val mode = if (settings.connectionType == ConnectionType.SIMULATOR) "SIM" else settings.usbAdapter.name
                Text(mode, color = GaugeColors.SubText, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                if (connection is ConnectionState.Disconnected) {
                    FilledTonalButton(onClick = vm::connect) { Text("接続") }
                } else {
                    OutlinedButton(onClick = vm::disconnect) { Text("切断") }
                }
                // 車載の画面でも押しやすいよう大きめに
                val big = Modifier.padding(start = 6.dp).size(56.dp)
                IconButton(onClick = { editing = true }, big) { Icon(Icons.Default.Edit, "レイアウト編集", Modifier.size(32.dp)) }
                IconButton(onClick = { onNavigate(Screen.LOG) }, big) { Icon(Icons.AutoMirrored.Filled.List, "通信ログ", Modifier.size(32.dp)) }
                IconButton(onClick = { onNavigate(Screen.SETTINGS) }, big) { Icon(Icons.Default.Settings, "設定", Modifier.size(32.dp)) }
            }
        }
    }

    // ---- ダッシュボード (左右スワイプで切替。編集中はスワイプしない) ----
    val pagerContent: @Composable (Modifier) -> Unit = { modifier ->
        HorizontalPager(pager, modifier, userScrollEnabled = !editing) { page ->
            dashboards.getOrNull(page)?.let { d ->
                DashboardCanvas(
                    dashboard = d,
                    values = values,
                    supported = supported,
                    catalog = catalog,
                    editing = editing,
                    onCommit = { g, x, y, w, h ->
                        vm.updateGauge(d.id, g.id) { it.copy(x = x, y = y, w = w, h = h) }
                    },
                    onTap = { g -> if (editing) settingsFor = g.id },
                    onCopy = { g ->
                        val group = Layout.groups(d.gauges)[g.layoutGroup]
                        if (group != null) vm.copyGauges(group) else vm.copyGauge(g)
                        toast(
                            (if (group != null) "グループ (${group.size} 個) を" else "") +
                                "コピーしました。下の「貼り付け」で、このページや別のページに貼り付けられます"
                        )
                    },
                    onCellAspect = vm::reportCellAspect,
                    onCellSize = vm::reportCellSize,
                    gridStep = settings.gridStep,
                    onMoveZ = { g, delta -> vm.moveZ(d.id, g.id, delta) },
                    onCommitMany = { placements -> vm.placeGauges(d.id, placements) },
                    selecting = selecting && d.id == current?.id,
                    selected = selected,
                    onToggleSelect = { g ->
                        // グループのメーターはグループごと選ぶ
                        val ids = Layout.groups(d.gauges)[g.layoutGroup]?.map { it.id }?.toSet() ?: setOf(g.id)
                        selected = if (g.id in selected) selected - ids else selected + ids
                    },
                )
            }
        }
    }

    // ---- 下部: 編集ツールバー / ページ名タブ ----
    val bottomBar: @Composable (Modifier) -> Unit = { modifier ->
        if (editing) {
            Row(
                modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 編集中もページを切り替えられるように (別のページへの貼り付け用)
                if (dashboards.size > 1) {
                    IconButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        enabled = pager.currentPage > 0,
                    ) { Text("◀") }
                }
                Text(current?.name ?: "", fontWeight = FontWeight.Bold, maxLines = 1)
                if (dashboards.size > 1) {
                    IconButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        enabled = pager.currentPage < dashboards.size - 1,
                    ) { Text("▶") }
                }
                Spacer(Modifier.weight(1f))
                if (selecting) {
                    val groupedSelected = current?.gauges?.any { it.id in selected && it.layoutGroup != null } == true
                    FilledTonalButton(
                        onClick = { current?.let { vm.groupGauges(it.id, selected) }; selecting = false; selected = emptySet() },
                        enabled = selected.size >= 2,
                    ) { Text("グループにする (${selected.size})") }
                    OutlinedButton(
                        onClick = { current?.let { vm.ungroupGauges(it.id, selected) }; selecting = false; selected = emptySet() },
                        enabled = groupedSelected,
                    ) { Text("グループ解除") }
                    OutlinedButton(onClick = { selecting = false; selected = emptySet() }) { Text("やめる") }
                } else {
                OutlinedButton(onClick = { selecting = true; selected = emptySet() }, enabled = current != null) { Text("グループ") }
                if (clipboard != null) {
                    FilledTonalButton(onClick = { current?.let { vm.pasteGauge(it.id) } }, enabled = current != null) {
                        Text("貼り付け")
                    }
                }
                FilledTonalButton(onClick = { pickerFor = 0L }, enabled = current != null) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("メーター追加")
                }
                Box {
                    OutlinedButton(onClick = { arrangeMenu = true }, enabled = current != null) { Text("並べ直す") }
                    DropdownMenu(expanded = arrangeMenu, onDismissRequest = { arrangeMenu = false }) {
                        (1..4).forEach { c ->
                            DropdownMenuItem(
                                text = { Text("$c 列で均等に並べる") },
                                onClick = { current?.let { vm.arrangeInGrid(it.id, c) }; arrangeMenu = false },
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { onNavigate(Screen.EDIT) }) { Text("ページ管理") }
                }
            }
        } else if (dashboards.size > 1) {
            Row(
                modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                dashboards.forEachIndexed { i, d ->
                    val selected = i == pager.currentPage
                    Text(
                        d.name,
                        color = if (selected) GaugeColors.Text else GaugeColors.SubText,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) GaugeColors.CardBorder else Color.Transparent)
                            .clickable { scope.launch { pager.animateScrollToPage(i) } }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    // 通常はメーターを全画面に出し、バーはボタンで呼び出す (編集中・「常に表示」設定のときは常に表示)
    // 編集中と完了後でメーターの大きさが変わらないよう、編集中もバーは上に重ねて表示する
    // (バーの分だけメーターの領域が縮むのは「常に表示」のときだけ。そのときは編集中も同じ)
    val barsPinned = settings.alwaysShowToolbar
    var barsShown by remember { mutableStateOf(false) }
    LaunchedEffect(barsShown, barsPinned) {
        if (barsShown && !barsPinned) {
            delay(6000)
            barsShown = false
        }
    }

    if (barsPinned) {
        Column(Modifier.fillMaxSize()) {
            topBar(Modifier)
            pagerContent(Modifier.weight(1f).fillMaxWidth())
            bottomBar(Modifier)
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            pagerContent(Modifier.fillMaxSize())
            if (editing) {
                // 背景だけの部分は触れても下のメーターに届く (ボタンだけが反応する)
                topBar(Modifier.align(Alignment.TopCenter).background(GaugeColors.Background.copy(alpha = 0.8f)))
                bottomBar(Modifier.align(Alignment.BottomCenter).background(GaugeColors.Background.copy(alpha = 0.8f)))
            } else if (barsShown) {
                // 画面のどこかをタップしたら閉じる
                Box(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { barsShown = false })
                topBar(Modifier.align(Alignment.TopCenter).background(GaugeColors.Background.copy(alpha = 0.92f)))
                bottomBar(Modifier.align(Alignment.BottomCenter).background(GaugeColors.Background.copy(alpha = 0.92f)))
            } else {
                MenuButton(connection, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 20.dp)) { barsShown = true }
            }
        }
    }

    // ---- メーターのデザイン編集 (全画面) ----
    val gaugeInSettings = current?.gauges?.firstOrNull { it.id == settingsFor }
    if (current != null && gaugeInSettings != null) {
        GaugeSettingsDialog(
            vm = vm,
            gauge = gaugeInSettings,
            catalog = catalog,
            supported = supported,
            liveValue = values[gaugeInSettings.pidKey],
            historySlot = HistorySlot.of(current.id, gaugeInSettings.id),
            onChange = { updated -> vm.updateGauge(current.id, updated.id) { updated } },
            onBringToFront = { vm.bringToFront(current.id, gaugeInSettings.id) },
            onMoveZ = { delta -> vm.moveZ(current.id, gaugeInSettings.id, delta) },
            onDelete = { vm.removeGauge(current.id, gaugeInSettings.id); settingsFor = null },
            onCreateCustomPid = { settingsFor = null; onCreateCustomPid() },
            onDismiss = { settingsFor = null },
        )
    } else if (settingsFor != null && current?.gauges?.none { it.id == settingsFor } == true) {
        LaunchedEffect(settingsFor) { settingsFor = null }
    }

    // ---- メーター追加: 項目選択 ----
    if (current != null && pickerFor != null) {
        ItemPickerDialog(
            catalog = catalog,
            supported = supported,
            onPick = { def ->
                // 空いている場所に置く。位置と大きさはドラッグで調整してもらう
                vm.addGauge(current.id, def.key, defaultStyleFor(def))
                if (!editing) editing = true
                pickerFor = null
            },
            onCreateCustom = { pickerFor = null; onCreateCustomPid() },
            onDismiss = { pickerFor = null },
        )
    }
}

/** 通常時に右上に出す小さなボタン。接続状態の色の点付き。タップでバーを呼び出す */
@Composable
private fun MenuButton(state: ConnectionState, modifier: Modifier, onClick: () -> Unit) {
    val dot = statusColor(state)
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(GaugeColors.Card.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MoreVert, "メニュー", Modifier.size(34.dp), tint = GaugeColors.Text.copy(alpha = 0.8f))
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(12.dp).clip(CircleShape).background(dot))
    }
}

/** 折れ線グラフの項目 (先頭はメーターの項目、続いて追加した項目) */
fun chartSeries(g: GaugeConfig, def: PidDef, values: Map<String, Double>, catalog: PidCatalog?): List<ChartSeries> =
    listOf(ChartSeries(def, values[def.key] ?: g.fallbackKey?.let { values[it] }, g.isRightAxis(0, def.key))) +
        g.chartKeys.mapIndexedNotNull { j, k ->
            catalog?.get(k)?.let { base ->
                // 表示名を変えてあればその名前で出す
                val def2 = g.itemLabels[k]?.takeIf { it.isNotBlank() }?.let { base.copy(shortName = it) } ?: base
                ChartSeries(def2, values[base.key], g.isRightAxis(j + 1, k))
            }
        }

/** 接続状態を表す色 (ダッシュボードとフロート表示の点) */
fun statusColor(state: ConnectionState): Color = when (state) {
    ConnectionState.Disconnected -> GaugeColors.SubText
    is ConnectionState.Connecting -> Color(0xFFFFC107)
    is ConnectionState.Connected -> Color(0xFF4CAF50)
    is ConnectionState.WaitingForVehicle -> Color(0xFF64B5F6)
    is ConnectionState.Error -> GaugeColors.Warn
}

fun defaultStyleFor(def: PidDef): GaugeStyle = when (def.key) {
    "RPM", "SPEED", "LOAD", "THROTTLE", "PEDAL", "BOOST", "TIMING" -> GaugeStyle.ANALOG
    "STFT1", "STFT2", "LTFT1", "LTFT2", "FUEL_LEVEL", "COOLANT", "OIL_TEMP" -> GaugeStyle.BAR
    else -> GaugeStyle.DIGITAL
}

@Composable
private fun StatusIndicator(state: ConnectionState, rate: Double, modifier: Modifier = Modifier) {
    val (color, text) = when (state) {
        ConnectionState.Disconnected -> GaugeColors.SubText to "未接続"
        is ConnectionState.Connecting -> Color(0xFFFFC107) to state.message
        is ConnectionState.Connected -> if (state.ignitionOff) {
            Color(0xFF4CAF50) to "接続中 (イグニッション OFF: エンジンの値は ON で表示)"
        } else {
            Color(0xFF4CAF50) to "接続中  %.1f PID/s".format(rate)
        }
        is ConnectionState.WaitingForVehicle -> Color(0xFF64B5F6) to (
            "車両の応答待ち (イグニッション ON で自動開始)" +
                (state.voltage?.let { "  バッテリー %.1fV".format(it) } ?: "")
            )
        is ConnectionState.Error -> GaugeColors.Warn to (state.message + if (state.retrying) "  (再接続待ち)" else "")
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = GaugeColors.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * メーターを [Layout.COLS] × [Layout.ROWS] のマス目に配置して描く。
 * 編集モードでは、ドラッグで移動、右下のつまみでサイズ変更 (マス目に吸着)、タップで設定。
 */
@Composable
private fun DashboardCanvas(
    dashboard: Dashboard,
    values: Map<String, Double>,
    supported: Set<Int>?,
    catalog: PidCatalog,
    editing: Boolean,
    onCommit: (GaugeConfig, Int, Int, Int, Int) -> Unit,
    onTap: (GaugeConfig) -> Unit,
    onCopy: (GaugeConfig) -> Unit = {},
    onCellAspect: (Float) -> Unit = {},
    onCellSize: (Float, Float) -> Unit = { _, _ -> },
    /** 移動・大きさ変更の吸着の間隔 (単位) */
    gridStep: Int = Layout.SCALE,
    /** 重なり順を変える (編集中の ▲ ▼ ボタン) */
    onMoveZ: (GaugeConfig, Int) -> Unit = { _, _ -> },
    /** グループの移動・大きさ変更の確定 (メーター id → x, y, w, h) */
    onCommitMany: (Map<Long, IntArray>) -> Unit = {},
    /** グループにするメーターを選んでいる */
    selecting: Boolean = false,
    selected: Set<Long> = emptySet(),
    onToggleSelect: (GaugeConfig) -> Unit = {},
) {
    // メーターごとのピーク値リセット回数 (長押しで増やす)
    val peakResets = remember { mutableStateMapOf<Long, Int>() }
    // 配置のグループと、グループをドラッグ中の移動量・大きさ変更量 (px)
    val groups = remember(dashboard.gauges) { Layout.groups(dashboard.gauges) }
    var groupMove by remember { mutableStateOf<Pair<Long, Offset>?>(null) }
    var groupResize by remember { mutableStateOf<Pair<Long, Offset>?>(null) }
    // 指を離してから保存が反映されるまでの間、元の位置に戻って見えないよう保持する
    var groupPending by remember { mutableStateOf<Map<Long, IntArray>?>(null) }
    LaunchedEffect(dashboard.gauges) { groupPending = null }
    // ページからはみ出した部分は切り取る (隣のページに映らないように)
    Box(Modifier.fillMaxSize().clipToBounds()) {
    // ページの背景 (色・柄・画像)。メーターは必ずこの上に描く
    PageBackgroundView(dashboard.background, Modifier.matchParentSize())
    BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
        val cellW = maxWidth / Layout.COLS
        val cellH = maxHeight / Layout.ROWS
        val density = LocalDensity.current
        val cellWPx = with(density) { cellW.toPx() }
        val cellHPx = with(density) { cellH.toPx() }
        LaunchedEffect(cellWPx, cellHPx) {
            if (cellHPx > 0f) onCellAspect(cellWPx / cellHPx)
            onCellSize(cellW.value, cellH.value)
        }

        if (editing) {
            // マス目のガイド
            Canvas(Modifier.fillMaxSize()) {
                // 吸着の間隔ごとに点を打つ (点が詰まりすぎるときは間引く)。1 マスごとの点は少し大きく
                var step = gridStep.coerceAtLeast(1)
                while (step * cellWPx < 6f && step < Layout.COLS) step *= 2
                for (x in 0..Layout.COLS step step) for (y in 0..Layout.ROWS step step) {
                    val major = x % Layout.SCALE == 0 && y % Layout.SCALE == 0
                    drawCircle(GaugeColors.CardBorder, if (major) 2f else 1.2f, Offset(x * cellWPx, y * cellHPx))
                }
            }
        }

        if (dashboard.gauges.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (editing) "下の「メーター追加」から追加してください"
                    else "メーターがありません。右上の鉛筆ボタンから追加してください",
                    color = GaugeColors.SubText,
                )
            }
        }

        /** グループをドラッグ中 (または確定待ち) の各メーターの配置 */
        fun groupPreview(): Map<Long, IntArray> {
            groupPending?.let { return it }
            groupMove?.let { (gid, o) ->
                val members = groups[gid] ?: return emptyMap()
                val b = Layout.bounds(members)
                val dx = if (o.x != 0f) Layout.snap(b[0] + (o.x / cellWPx).roundToInt(), gridStep) - b[0] else 0
                val dy = if (o.y != 0f) Layout.snap(b[1] + (o.y / cellHPx).roundToInt(), gridStep) - b[1] else 0
                return Layout.moveGroup(members, dx, dy)
            }
            groupResize?.let { (gid, o) ->
                val members = groups[gid] ?: return emptyMap()
                val b = Layout.bounds(members)
                val nw = if (o.x != 0f) Layout.snap(b[0] + b[2] + (o.x / cellWPx).roundToInt(), gridStep) - b[0] else b[2]
                val nh = if (o.y != 0f) Layout.snap(b[1] + b[3] + (o.y / cellHPx).roundToInt(), gridStep) - b[1] else b[3]
                return Layout.resizeGroup(members, nw, nh)
            }
            return emptyMap()
        }
        val preview = groupPreview()
        fun commitGroup() {
            val p = groupPreview()
            groupMove = null; groupResize = null
            val changed = p.filter { (id, r) ->
                dashboard.gauges.firstOrNull { it.id == id }?.let { g -> g.x != r[0] || g.y != r[1] || g.w != r[2] || g.h != r[3] } == true
            }
            if (changed.isNotEmpty()) { groupPending = p; onCommitMany(changed) }
        }

        dashboard.gauges.forEachIndexed { zi, g ->
            key(g.id) {
                val def = g.resolvedDef(catalog)
                val gid = g.layoutGroup?.takeIf { it in groups }
                val needle = remember(g.id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
                GaugeOnGrid(
                    gauge = g,
                    cellWPx = cellWPx,
                    cellHPx = cellHPx,
                    editing = editing,
                    onCommit = onCommit,
                    onTap = onTap,
                    onLongPress = { peakResets[g.id] = (peakResets[g.id] ?: 0) + 1 },
                    onCopy = onCopy,
                    grouped = gid != null,
                    override = preview[g.id],
                    onGroupDrag = { d -> if (gid != null) groupMove = gid to ((groupMove?.second ?: Offset.Zero) + d) },
                    onGroupDragEnd = { commitGroup() },
                    onGroupDragCancel = { groupMove = null },
                    selecting = selecting,
                    selected = g.id in selected,
                    onToggleSelect = onToggleSelect,
                    gridStep = gridStep,
                    onMoveZ = onMoveZ,
                    // 重なり順はリストの順番。描く順を明示して、並べ替えたらすぐ反映されるようにする
                    zOrder = zi.toFloat(),
                    // アナログの針は、ほかのメーター (中に重ねて置いたものも) より手前に描く
                    // (グループにするメーターを選んでいる間は、選んだ印が隠れないよう針もメーターと一緒に描く)
                    overlay = if (g.style == GaugeStyle.ANALOG && def != null && !(editing && selecting)) {
                        { GaugeView(g, def, values, supported, layer = com.obdmeter.ui.gauges.GaugeLayer.NEEDLE, needleState = needle) }
                    } else null,
                ) {
                    GaugeView(g, def, values, supported, peakResets[g.id] ?: 0, catalog = catalog, interactive = !editing,
                        historySlot = HistorySlot.of(dashboard.id, g.id),
                        layer = if (g.style == GaugeStyle.ANALOG && !(editing && selecting)) com.obdmeter.ui.gauges.GaugeLayer.BODY else com.obdmeter.ui.gauges.GaugeLayer.ALL,
                        needleState = needle)
                }
            }
        }

        // グループの囲み (点線) と、グループの大きさ変更のつまみ
        if (editing && !selecting) {
            groups.forEach { (gid, members) ->
                key("group", gid) {
                    val b = members.map { m -> preview[m.id] ?: intArrayOf(m.x, m.y, m.w, m.h) }.let { rs ->
                        val x0 = rs.minOf { it[0] }; val y0 = rs.minOf { it[1] }
                        intArrayOf(x0, y0, rs.maxOf { it[0] + it[2] } - x0, rs.maxOf { it[1] + it[3] } - y0)
                    }
                    val active = groupMove?.first == gid || groupResize?.first == gid
                    val commitNow by rememberUpdatedState({ commitGroup() })
                    val ox = with(density) { (b[0] * cellWPx).toDp() }
                    val oy = with(density) { (b[1] * cellHPx).toDp() }
                    val ow = with(density) { (b[2] * cellWPx).toDp() }
                    val oh = with(density) { (b[3] * cellHPx).toDp() }
                    // 点線の囲みはメーターの後ろに描く (全体を覆う層を手前に置くと、Web 版でメーターのタップが届かなかった)
                    Canvas(Modifier.zIndex(-1f).offset(ox, oy).size(ow, oh)) {
                        drawRoundRect(
                            if (active) GaugeColors.Warn else GaugeColors.Peak,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                2.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                            ),
                        )
                    }
                    // 右上: グループの印 (▲▼ ボタンと重ならないよう、囲みの上の辺に載せる)
                    Box(
                        Modifier.zIndex(NEEDLE_Z + 1f).offset(ox, oy).size(ow, 0.dp).wrapContentSize(Alignment.TopEnd, unbounded = true)
                    ) {
                        Box(
                            Modifier.offset(x = (-44).dp, y = (-9).dp).clip(RoundedCornerShape(11.dp))
                                .background(GaugeColors.Peak).padding(horizontal = 6.dp, vertical = 2.dp),
                        ) { Text("グループ", color = GaugeColors.Background, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                    // 右下: グループの大きさ変更のつまみ
                    Box(
                        Modifier
                            .zIndex(NEEDLE_Z + 1f)
                            .offset(ox + ow - 40.dp, oy + oh - 40.dp)
                            .size(40.dp)
                            .pointerInput(gid) {
                                detectDragGestures(
                                    onDragEnd = { commitNow() },
                                    onDragCancel = { groupResize = null },
                                ) { change, amount ->
                                    change.consume()
                                    groupResize = gid to ((groupResize?.second ?: Offset.Zero) + amount)
                                }
                            },
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        Canvas(Modifier.size(28.dp).padding(3.dp)) {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
                            }
                            drawPath(path, GaugeColors.Peak)
                        }
                    }
                }
            }
        }
    }
    }
}

/** アナログの針の層の描く順番 (どのメーターよりも手前) */
private const val NEEDLE_Z = 100_000f

/** メーター 1 つ (アプリのダッシュボードとフロート表示で共通) */
@Composable
fun GaugeView(
    g: GaugeConfig,
    def: PidDef?,
    values: Map<String, Double>,
    supported: Set<Int>?,
    peakResetKey: Int = 0,
    /** true = 四角いカードの背景を描かない (フロート表示で背景を透過させる) */
    noCard: Boolean = false,
    /** 折れ線グラフの追加項目を探すのに使う */
    catalog: PidCatalog? = null,
    /** 折れ線グラフをドラッグで操作できるか */
    interactive: Boolean = false,
    /** 折れ線グラフの記録の置き場所 ([HistorySlot]) */
    historySlot: String? = null,
    /** 描く部分 (アナログの針を別の層にして、ほかのメーターより手前に出すとき) */
    layer: com.obdmeter.ui.gauges.GaugeLayer = com.obdmeter.ui.gauges.GaugeLayer.ALL,
    needleState: androidx.compose.runtime.MutableFloatState? = null,
) {
    if (def != null) {
        GaugeCard(
            def = def,
            // メインの値が無いときは代替項目の値を出す (イグニッション OFF 時など)
            value = values[def.key] ?: g.fallbackKey?.let { values[it] },
            style = g.style,
            unsupported = isUnsupported(def, supported) && g.fallbackKey == null,
            look = g.look(def).let { if (noCard) it.copy(showCard = false) else it },
            modifier = Modifier.fillMaxSize(),
            peakResetKey = peakResetKey,
            series = if (g.style == GaugeStyle.CHART || g.style == GaugeStyle.TABLE || g.style == GaugeStyle.MONITOR) chartSeries(g, def, values, catalog) else emptyList(),
            interactive = interactive,
            historySlot = historySlot,
            layer = layer,
            needleState = needleState,
        )
    } else if (layer != com.obdmeter.ui.gauges.GaugeLayer.NEEDLE) {
        // 削除されたカスタム PID など
        Box(
            Modifier.fillMaxSize().background(GaugeColors.Card, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("項目が見つかりません\n(${g.pidKey})", color = GaugeColors.SubText, fontSize = 12.sp) }
    }
}

@Composable
private fun GaugeOnGrid(
    gauge: GaugeConfig,
    cellWPx: Float,
    cellHPx: Float,
    editing: Boolean,
    onCommit: (GaugeConfig, Int, Int, Int, Int) -> Unit,
    onTap: (GaugeConfig) -> Unit,
    onLongPress: () -> Unit,
    onCopy: (GaugeConfig) -> Unit = {},
    /** 配置のグループに入っている (ドラッグでグループごと動かす。個別の大きさ変更はしない) */
    grouped: Boolean = false,
    /** グループのドラッグ中などに外から決まる配置 */
    override: IntArray? = null,
    onGroupDrag: (Offset) -> Unit = {},
    onGroupDragEnd: () -> Unit = {},
    onGroupDragCancel: () -> Unit = {},
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (GaugeConfig) -> Unit = {},
    /** ほかのメーターより手前に重ねて描くもの (アナログの針)。触っても下のメーターに届く */
    overlay: (@Composable () -> Unit)? = null,
    /** 移動・大きさ変更の吸着の間隔 (単位) */
    gridStep: Int = Layout.SCALE,
    /** 重なり順を変える (編集中の ▲ ▼ ボタン)。+MAX = 最前面、MIN = 最背面 */
    onMoveZ: (GaugeConfig, Int) -> Unit = { _, _ -> },
    /** 描く順番 (大きいほど手前) */
    zOrder: Float = 0f,
    content: @Composable () -> Unit,
) {
    val isGrouped by rememberUpdatedState(grouped)
    val groupDrag by rememberUpdatedState(onGroupDrag)
    val groupDragEnd by rememberUpdatedState(onGroupDragEnd)
    val groupDragCancel by rememberUpdatedState(onGroupDragCancel)
    val toggleSelect by rememberUpdatedState(onToggleSelect)
    val moveZ by rememberUpdatedState(onMoveZ)
    val g by rememberUpdatedState(gauge)
    // ドラッグ中の累積移動量 (px)
    var move by remember { mutableStateOf(Offset.Zero) }
    var resize by remember { mutableStateOf(Offset.Zero) }
    // 指を離してから保存が反映されるまでの間、元の位置に戻って見えないよう保持する
    var pending by remember { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(gauge.x, gauge.y, gauge.w, gauge.h) { pending = null }

    fun preview(): IntArray {
        override?.let { return it }
        pending?.let { return it }
        // 動かした・大きさを変えた向きだけ、グリッドの間隔 [gridStep] に吸着させる
        val x = if (move.x != 0f) Layout.snap(g.x + (move.x / cellWPx).roundToInt(), gridStep) else g.x
        val y = if (move.y != 0f) Layout.snap(g.y + (move.y / cellHPx).roundToInt(), gridStep) else g.y
        val w = if (resize.x != 0f) Layout.snap(g.x + g.w + (resize.x / cellWPx).roundToInt(), gridStep) - g.x else g.w
        val h = if (resize.y != 0f) Layout.snap(g.y + g.h + (resize.y / cellHPx).roundToInt(), gridStep) - g.y else g.h
        // ページの外へのはみ出しも可 (つかめるよう、少なくとも 1 マスはページ内に残す)
        return Layout.clampPlacement(x, y, w, h)
    }

    val r = preview()
    val density = LocalDensity.current
    val dragging = move != Offset.Zero || resize != Offset.Zero || override != null

    // 手前の層 (アナログの針)。同じ位置・大きさで、ほかのメーターより上に重ねる
    overlay?.let { o ->
        Box(
            Modifier
                .zIndex(NEEDLE_Z)
                .offset(with(density) { (r[0] * cellWPx).toDp() }, with(density) { (r[1] * cellHPx).toDp() })
                .size(with(density) { (r[2] * cellWPx).toDp() }, with(density) { (r[3] * cellHPx).toDp() })
                .padding(3.dp)
        ) { o() }
    }

    Box(
        Modifier
            .zIndex(zOrder)
            .offset(with(density) { (r[0] * cellWPx).toDp() }, with(density) { (r[1] * cellHPx).toDp() })
            .size(with(density) { (r[2] * cellWPx).toDp() }, with(density) { (r[3] * cellHPx).toDp() })
            .padding(3.dp)
            .then(
                if (editing && selecting) Modifier
                    // グループにするメーターを選ぶ
                    .border(if (selected) 4.dp else 2.dp, if (selected) GaugeColors.Peak else GaugeColors.CardBorder, RoundedCornerShape(14.dp))
                    .pointerInput(gauge.id) { detectTapGestures(onTap = { toggleSelect(g) }) }
                else if (editing) Modifier
                    .border(2.dp, if (dragging) GaugeColors.Warn else GaugeColors.Accent, RoundedCornerShape(14.dp))
                    // タップ (設定)・長押し (コピー)・ドラッグ (移動) を 1 つの処理で見分ける
                    // (別々の検出を重ねると、グループのメーターで Web 版のタップが効かなかったため)
                    .pointerInput(gauge.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = viewConfiguration.touchSlop
                            val longMs = viewConfiguration.longPressTimeoutMillis
                            val started = kotlin.time.TimeSource.Monotonic.markNow()
                            var dragging = false
                            var longDone = false
                            var total = Offset.Zero
                            var finished = false
                            fun dragBy(amount: Offset) { if (isGrouped) groupDrag(amount) else move += amount }
                            try {
                                while (true) {
                                    val event = if (!dragging && !longDone) {
                                        val left = longMs - started.elapsedNow().inWholeMilliseconds
                                        if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent() }
                                    } else awaitPointerEvent()
                                    if (event == null) {
                                        // 長押し: コピー
                                        longDone = true
                                        onCopy(g)
                                        continue
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        if (dragging) {
                                            finished = true
                                            if (isGrouped) groupDragEnd()
                                            else {
                                                val p = preview()
                                                pending = p
                                                move = Offset.Zero
                                                if (p[0] != g.x || p[1] != g.y) onCommit(g, p[0], p[1], p[2], p[3]) else pending = null
                                            }
                                        } else if (!longDone) onTap(g)
                                        break
                                    }
                                    val delta = change.position - change.previousPosition
                                    if (dragging) {
                                        change.consume()
                                        dragBy(delta)
                                    } else {
                                        total += delta
                                        if (total.getDistance() > slop) {
                                            dragging = true
                                            change.consume()
                                            dragBy(total)
                                        }
                                    }
                                }
                            } finally {
                                if (dragging && !finished) { if (isGrouped) groupDragCancel() else move = Offset.Zero }
                            }
                        }
                    }
                else Modifier.pointerInput(gauge.id) {
                    // 通常表示中は長押しでピーク値をリセット
                    detectTapGestures(onLongPress = { onLongPress() })
                }
            )
    ) {
        content()
        if (editing && !gauge.floatShow) {
            // フロート表示しないメーター (左上)
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(11.dp))
                    .background(GaugeColors.SubText).padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) { Text("フロートなし", color = GaugeColors.Background, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        } else if (editing && gauge.floatGroup != null) {
            // フロート表示のグループ (左上)
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp).clip(CircleShape)
                    .background(GaugeColors.Accent),
                contentAlignment = Alignment.Center,
            ) { Text(GaugeConfig.groupLabel(gauge.floatGroup), color = GaugeColors.Background, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        if (editing && selecting && selected) {
            Box(
                Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape).background(GaugeColors.Peak),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Check, null, tint = GaugeColors.Background) }
        }
        if (editing && !selecting) {
            // 重なり順 (右上): ▲ 最前面 / ▼ 最背面
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("▲" to Int.MAX_VALUE, "▼" to Int.MIN_VALUE).forEach { (label, delta) ->
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                            .background(GaugeColors.Background.copy(alpha = 0.75f))
                            .border(1.dp, GaugeColors.Accent, RoundedCornerShape(8.dp))
                            .pointerInput(gauge.id, delta) { detectTapGestures(onTap = { moveZ(g, delta) }) },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = GaugeColors.Accent, fontSize = 13.sp) }
                }
            }
        }
        if (editing && !selecting && !grouped) {
            // サイズ変更のつまみ (右下)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(36.dp)
                    .pointerInput(gauge.id) {
                        detectDragGestures(
                            onDragEnd = {
                                val p = preview()
                                pending = p
                                resize = Offset.Zero
                                if (p[2] != g.w || p[3] != g.h) onCommit(g, p[0], p[1], p[2], p[3]) else pending = null
                            },
                            onDragCancel = { resize = Offset.Zero },
                        ) { change, amount ->
                            change.consume()
                            resize += amount
                        }
                    },
                contentAlignment = Alignment.BottomEnd,
            ) {
                Canvas(Modifier.size(22.dp).padding(4.dp)) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
                    }
                    drawPath(path, GaugeColors.Accent)
                }
            }
        }
    }
}

fun isUnsupported(def: PidDef, supported: Set<Int>?): Boolean {
    if (supported == null) return false
    return when (def.kind) {
        PidDef.Kind.MODE01 -> def.pid !in supported
        PidDef.Kind.ADAPTER_VOLTAGE, PidDef.Kind.CUSTOM, PidDef.Kind.REQUEST, PidDef.Kind.BMW_BLOCK, PidDef.Kind.DEVICE -> false
        PidDef.Kind.DERIVED -> def.deps.mapNotNull { Pids.get(it) }.any { isUnsupported(it, supported) }
    }
}

/** 一覧などで使う短い説明 ("カスタム 22 4205" / "PID 0C") */
fun PidDef.sourceLabel(): String = when (kind) {
    PidDef.Kind.MODE01 -> "PID %02X".format(pid)
    PidDef.Kind.CUSTOM -> "カスタム $request"
    PidDef.Kind.REQUEST -> "OBD Mode %02X MID %s".format(request?.mode ?: 0, request?.pid?.joinToString("") { "%02X".format(it) } ?: "")
    PidDef.Kind.BMW_BLOCK -> "BMW DME 測定値 %04X".format(blockId)
    PidDef.Kind.ADAPTER_VOLTAGE -> "アダプター測定"
    PidDef.Kind.DERIVED -> "計算値"
    PidDef.Kind.DEVICE -> "端末のセンサー"
}

