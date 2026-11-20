package com.obdmeter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdmeter.data.Dashboard
import com.obdmeter.ui.gauges.GaugeColors

/**
 * ページ (ダッシュボード) の追加・名前変更・並べ替え・削除。
 * メーターの配置や見た目は、ダッシュボード画面の鉛筆ボタン (レイアウト編集) で行う。
 */
@Composable
fun EditScreen(vm: MainViewModel, onBack: () -> Unit) {
    val dashboards by vm.dashboards.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<Dashboard?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    /** 背景を編集しているページの id */
    var backgroundFor by remember { mutableStateOf<Long?>(null) }
    /** チェックしたページ (まとめて削除) */
    var checked by remember { mutableStateOf(emptySet<Long>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    /** ドラッグで並べ替え中の順番 (ページの id)・ドラッグしているページ・ずれ・1 行の高さ */
    var dragOrder by remember { mutableStateOf<List<Long>?>(null) }
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var rowHeight by remember { mutableStateOf(1f) }
    val rowHeights = remember { HashMap<Long, Int>() }
    val shownList = dragOrder?.let { o -> o.mapNotNull { id -> dashboards.firstOrNull { it.id == id } } } ?: dashboards

    Column(Modifier.fillMaxSize()) {
        TopBar("ページ管理", onBack)
        Text(
            "左右スワイプで切り替わるページの一覧です。左のつまみ (三本線) をドラッグすると並べ替え、チェックを付けるとまとめて削除できます。「隠す」にしたページは、ふだんのページ切り替えには出ず、レイアウト編集中だけ出ます。「背景」でページの背景 (色・柄・画像) を変えられます。メーターの配置や見た目は、ダッシュボードの鉛筆ボタンで編集します。",
            color = GaugeColors.SubText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (checked.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${checked.size} ページを選択中", modifier = Modifier.weight(1f))
                TextButton(onClick = { checked = dashboards.map { it.id }.toSet() }) { Text("全部") }
                TextButton(onClick = { checked = emptySet() }) { Text("選択をやめる") }
                TextButton(onClick = { confirmBulkDelete = true }, enabled = checked.size < dashboards.size) {
                    Icon(Icons.Default.Delete, null, tint = GaugeColors.Warn); Text("削除", color = GaugeColors.Warn)
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            itemsIndexed(shownList, key = { _, d -> d.id }) { index, d ->
                val dragging = d.id == dragId
                Row(
                    Modifier.fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .background(if (dragging) GaugeColors.CardBorder else androidx.compose.ui.graphics.Color.Transparent)
                        .onSizeChanged { rowHeights[d.id] = it.height }
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ドラッグして並べ替える
                    Icon(
                        Icons.Default.Menu, "並べ替え", tint = GaugeColors.SubText,
                        modifier = Modifier.padding(horizontal = 8.dp).pointerInput(d.id) {
                            detectDragGestures(
                                onDragStart = {
                                    dragOrder = dashboards.map { it.id }
                                    dragId = d.id
                                    dragOffset = 0f
                                    rowHeight = (rowHeights[d.id] ?: (size.height * 2)).toFloat().coerceAtLeast(1f)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val order = dragOrder ?: return@detectDragGestures
                                    val i = order.indexOf(d.id)
                                    // 行の半分を越えたら隣と入れ替える
                                    if (dragOffset > rowHeight / 2 && i < order.lastIndex) {
                                        dragOrder = order.toMutableList().also { it[i] = it[i + 1]; it[i + 1] = d.id }
                                        dragOffset -= rowHeight
                                    } else if (dragOffset < -rowHeight / 2 && i > 0) {
                                        dragOrder = order.toMutableList().also { it[i] = it[i - 1]; it[i - 1] = d.id }
                                        dragOffset += rowHeight
                                    }
                                },
                                onDragEnd = {
                                    dragOrder?.let { if (it != dashboards.map { x -> x.id }) vm.reorderDashboards(it) }
                                    dragId = null; dragOffset = 0f
                                    // 保存が反映されるまで少し待ってから、並べ替え中の順番をやめる
                                    scope.launch { kotlinx.coroutines.delay(300); if (dragId == null) dragOrder = null }
                                },
                                onDragCancel = { dragId = null; dragOffset = 0f; dragOrder = null },
                            )
                        },
                    )
                    Checkbox(
                        checked = d.id in checked,
                        onCheckedChange = { on -> checked = if (on) checked + d.id else checked - d.id },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("${index + 1}. ${d.name}" + if (d.hidden) "  (隠しページ)" else "", fontWeight = FontWeight.Medium,
                            color = if (d.hidden) GaugeColors.SubText else androidx.compose.ui.graphics.Color.Unspecified)
                        val items = d.gauges.mapNotNull { catalog.get(it.pidKey)?.shortName }.joinToString("・")
                        Text(items.ifEmpty { "メーターなし" }, color = GaugeColors.SubText, fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(
                        onClick = { vm.setDashboardHidden(d.id, !d.hidden) },
                        // 最後の 1 ページは隠せない (ふだん出すページが無くなるので)
                        enabled = d.hidden || dashboards.count { !it.hidden } > 1,
                    ) { Text(if (d.hidden) "出す" else "隠す") }
                    TextButton(onClick = { backgroundFor = d.id }) { Text("背景") }
                    IconButton(onClick = { nameDialog = NameDialog(d.id, d.name) }) { Icon(Icons.Default.Edit, "名前変更") }
                    IconButton(onClick = { vm.moveDashboard(d.id, -1) }, enabled = index > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, "前へ")
                    }
                    IconButton(onClick = { vm.moveDashboard(d.id, 1) }, enabled = index < dashboards.lastIndex) {
                        Icon(Icons.Default.KeyboardArrowDown, "後ろへ")
                    }
                    IconButton(onClick = { deleteTarget = d }, enabled = dashboards.size > 1) {
                        Icon(Icons.Default.Delete, "削除")
                    }
                }
                HorizontalDivider()
            }
            item {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    TextButton(onClick = { nameDialog = NameDialog(null, "新しいページ") }) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("ページを追加")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmReset = true }) { Text("初期状態に戻す", color = GaugeColors.SubText) }
                }
            }
        }
    }

    dashboards.firstOrNull { it.id == backgroundFor }?.let { d ->
        PageBackgroundDialog(vm, d) { backgroundFor = null }
    }

    nameDialog?.let { dlg ->
        var text by remember(dlg) { mutableStateOf(dlg.initial) }
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (dlg.id == null) "ページを追加" else "名前を変更") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val name = text.trim().ifEmpty { "ページ" }
                    if (dlg.id == null) vm.addDashboard(name) else vm.renameDashboard(dlg.id, name)
                    nameDialog = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("キャンセル") } },
        )
    }

    if (confirmBulkDelete) {
        val targets = dashboards.filter { it.id in checked }
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("${targets.size} ページを削除しますか？") },
            text = { Text(targets.joinToString("、") { it.name } + "\nこれらのページのメーター ${targets.sumOf { it.gauges.size }} 個も削除されます。「元に戻す」(レイアウト編集) で戻せます。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteDashboards(checked); checked = emptySet(); confirmBulkDelete = false }) {
                    Text("削除", color = GaugeColors.Warn)
                }
            },
            dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("キャンセル") } },
        )
    }

    deleteTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("「${d.name}」を削除しますか？") },
            text = { Text("このページのメーター ${d.gauges.size} 個も削除されます。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteDashboard(d.id); deleteTarget = null }) { Text("削除", color = GaugeColors.Warn) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("ページとメーターを初期状態に戻しますか？") },
            text = { Text("カスタム PID の登録は消えません。") },
            confirmButton = {
                TextButton(onClick = { vm.resetDashboards(); confirmReset = false }) { Text("戻す", color = GaugeColors.Warn) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("キャンセル") } },
        )
    }
}

private data class NameDialog(val id: Long?, val initial: String)

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}
