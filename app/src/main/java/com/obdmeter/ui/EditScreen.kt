package com.obdmeter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdmeter.core.Pids
import com.obdmeter.data.GaugeConfig
import com.obdmeter.data.GaugeStyle
import com.obdmeter.ui.gauges.GaugeColors

@Composable
fun EditScreen(vm: MainViewModel, onBack: () -> Unit) {
    val dashboards by vm.dashboards.collectAsStateWithLifecycle()
    val supported by vm.supported.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(dashboards.firstOrNull()?.id ?: 0L) }
    val selected = dashboards.firstOrNull { it.id == selectedId } ?: dashboards.firstOrNull()

    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var showAddGauge by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar("メーター編集", onBack)

        // ---- ダッシュボード (ページ) 一覧 ----
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(dashboards, key = { it.id }) { d ->
                FilterChip(
                    selected = d.id == selected?.id,
                    onClick = { selectedId = d.id },
                    label = { Text(d.name) },
                )
            }
            item {
                TextButton(onClick = { nameDialog = NameDialog(null, "新しいページ") }) {
                    Icon(Icons.Default.Add, null); Text("ページ追加")
                }
            }
        }

        if (selected != null) {

        // ---- 選択中ページの設定 ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(selected.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { nameDialog = NameDialog(selected.id, selected.name) }) { Icon(Icons.Default.Edit, "名前変更") }
            IconButton(onClick = { vm.moveDashboard(selected.id, -1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "左へ") }
            IconButton(onClick = { vm.moveDashboard(selected.id, 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "右へ") }
            IconButton(onClick = { confirmDelete = true }, enabled = dashboards.size > 1) { Icon(Icons.Default.Delete, "ページ削除") }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("列数", color = GaugeColors.SubText)
            Spacer(Modifier.width(12.dp))
            (1..4).forEach { c ->
                FilterChip(
                    selected = selected.columns == c,
                    onClick = { vm.setColumns(selected.id, c) },
                    label = { Text("$c") },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- メーター一覧 ----
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(selected.gauges) { index, g ->
                val def = Pids.get(g.pidKey)
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(def?.name ?: g.pidKey, fontWeight = FontWeight.Medium)
                            val note = if (def != null && isUnsupported(def, supported)) "  ・この車両は非対応" else ""
                            Text((def?.unit ?: "") + note, color = GaugeColors.SubText, fontSize = 12.sp)
                        }
                        IconButton(onClick = { vm.moveGauge(selected.id, index, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "上へ") }
                        IconButton(onClick = { vm.moveGauge(selected.id, index, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "下へ") }
                        IconButton(onClick = { vm.removeGauge(selected.id, index) }) { Icon(Icons.Default.Delete, "削除") }
                    }
                    Row {
                        GaugeStyle.entries.forEach { s ->
                            FilterChip(
                                selected = g.style == s,
                                onClick = { vm.updateGauge(selected.id, index, g.copy(style = s)) },
                                label = { Text(s.label) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            item {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { showAddGauge = true }) { Icon(Icons.Default.Add, null); Text("メーターを追加") }
                    TextButton(onClick = { vm.resetDashboards() }) { Text("初期状態に戻す", color = GaugeColors.SubText) }
                }
            }
        }
        } // if (selected != null)
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
                    if (dlg.id == null) selectedId = vm.addDashboard(name) else vm.renameDashboard(dlg.id, name)
                    nameDialog = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("キャンセル") } },
        )
    }

    if (confirmDelete && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("「${selected.name}」を削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDashboard(selected.id)
                    selectedId = dashboards.firstOrNull { it.id != selected.id }?.id ?: 0L
                    confirmDelete = false
                }) { Text("削除", color = GaugeColors.Warn) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }

    if (showAddGauge && selected != null) {
        AlertDialog(
            onDismissRequest = { showAddGauge = false },
            title = { Text("メーターを追加") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(Pids.ALL) { def ->
                        val unsupported = isUnsupported(def, supported)
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    vm.addGauge(selected.id, GaugeConfig(def.key, defaultStyleFor(def.key)))
                                    showAddGauge = false
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(def.name, color = if (unsupported) GaugeColors.SubText else GaugeColors.Text)
                            Text(
                                def.unit + if (unsupported) "  ・この車両は非対応" else "",
                                color = GaugeColors.SubText, fontSize = 12.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddGauge = false }) { Text("閉じる") } },
        )
    }
}

private data class NameDialog(val id: Long?, val initial: String)

private fun defaultStyleFor(key: String): GaugeStyle = when (key) {
    "RPM", "SPEED", "LOAD", "THROTTLE", "PEDAL", "BOOST", "TIMING" -> GaugeStyle.ANALOG
    "STFT1", "STFT2", "LTFT1", "LTFT2", "FUEL_LEVEL", "COOLANT", "OIL_TEMP" -> GaugeStyle.BAR
    else -> GaugeStyle.DIGITAL
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}
