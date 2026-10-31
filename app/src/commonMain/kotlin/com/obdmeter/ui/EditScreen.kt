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

    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<Dashboard?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    /** 背景を編集しているページの id */
    var backgroundFor by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("ページ管理", onBack)
        Text(
            "左右スワイプで切り替わるページの一覧です。「隠す」にしたページは、ふだんのページ切り替えには出ず、レイアウト編集中だけ出ます。「背景」でページの背景 (色・柄・画像) を変えられます。メーターの配置や見た目は、ダッシュボードの鉛筆ボタンで編集します。",
            color = GaugeColors.SubText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            itemsIndexed(dashboards, key = { _, d -> d.id }) { index, d ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
