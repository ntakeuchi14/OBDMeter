package com.obdmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obdmeter.core.PidCatalog
import com.obdmeter.core.PidDef
import com.obdmeter.data.GaugeConfig
import com.obdmeter.data.GaugeStyle
import com.obdmeter.data.Layout
import com.obdmeter.ui.gauges.GaugeColors

/**
 * メーター 1 個の設定。変更はその場でダッシュボードに反映される (後ろに見えている)。
 */
@Composable
fun GaugeSettingsDialog(
    gauge: GaugeConfig,
    catalog: PidCatalog,
    onChange: (GaugeConfig) -> Unit,
    onChangeItem: () -> Unit,
    onBringToFront: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val base = catalog.get(gauge.pidKey)
    val d = gauge.design
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("メーターの設定") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                // ---- 表示項目 ----
                Label("表示する項目")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(base?.name ?: "(見つかりません: ${gauge.pidKey})", fontWeight = FontWeight.Medium)
                        if (base != null) Text("${base.sourceLabel()}  ${base.unit}", color = GaugeColors.SubText, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onChangeItem) { Text("変更") }
                }

                // ---- 表示形式 ----
                Label("表示形式")
                Row {
                    GaugeStyle.entries.forEach { s ->
                        FilterChip(
                            selected = gauge.style == s,
                            onClick = { onChange(gauge.copy(style = s)) },
                            label = { Text(s.label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }

                // ---- 表示名 ----
                Label("表示名 (空欄で「${base?.shortName ?: ""}」)")
                var labelText by remember(gauge.id) { mutableStateOf(d.label.orEmpty()) }
                OutlinedTextField(
                    value = labelText,
                    onValueChange = {
                        labelText = it
                        onChange(gauge.copy(design = d.copy(label = it.ifBlank { null })))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ---- 範囲 ----
                Label("目盛りの範囲 (空欄で既定値)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("最小", d.min, base?.min, gauge.id, Modifier.weight(1f)) {
                        onChange(gauge.copy(design = d.copy(min = it)))
                    }
                    NumberField("最大", d.max, base?.max, gauge.id, Modifier.weight(1f)) {
                        onChange(gauge.copy(design = d.copy(max = it)))
                    }
                }

                // ---- 小数桁 ----
                Label("小数点以下の桁数")
                Row {
                    val options = listOf<Int?>(null, 0, 1, 2, 3)
                    options.forEach { n ->
                        FilterChip(
                            selected = d.decimals == n,
                            onClick = { onChange(gauge.copy(design = d.copy(decimals = n))) },
                            label = { Text(n?.toString() ?: "既定") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }

                // ---- 警告値 ----
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("既定の警告値を使う")
                        val def = listOfNotNull(
                            base?.warnAbove?.let { "上限 ${fmt(it)}" },
                            base?.warnBelow?.let { "下限 ${fmt(it)}" },
                        ).joinToString(" / ").ifEmpty { "警告なし" }
                        Text(def, color = GaugeColors.SubText, fontSize = 12.sp)
                    }
                    Switch(d.useDefaultWarn, { onChange(gauge.copy(design = d.copy(useDefaultWarn = it))) })
                }
                if (!d.useDefaultWarn) {
                    Text("値がこの範囲を超えるとメーターが赤くなります (空欄 = 警告なし)",
                        color = GaugeColors.SubText, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("下限", d.warnBelow, null, gauge.id, Modifier.weight(1f)) {
                            onChange(gauge.copy(design = d.copy(warnBelow = it)))
                        }
                        NumberField("上限", d.warnAbove, null, gauge.id, Modifier.weight(1f)) {
                            onChange(gauge.copy(design = d.copy(warnAbove = it)))
                        }
                    }
                }

                // ---- 色 ----
                Label("色")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(GaugeColors.Accent, d.color == null, "既定") { onChange(gauge.copy(design = d.copy(color = null))) }
                    Layout.COLORS.forEach { (name, argb) ->
                        ColorDot(Color(argb), d.color == argb, name) { onChange(gauge.copy(design = d.copy(color = argb))) }
                    }
                }

                // ---- 配置 ----
                Label("配置")
                Text("位置 (${gauge.x}, ${gauge.y})  大きさ ${gauge.w} × ${gauge.h} マス (全体 ${Layout.COLS} × ${Layout.ROWS})",
                    color = GaugeColors.SubText, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = onBringToFront) { Text("最前面へ") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("削除", color = GaugeColors.Warn) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("このメーターを削除しますか？") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("削除", color = GaugeColors.Warn) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
}

private fun fmt(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

@Composable
private fun Label(text: String) {
    Text(text, color = GaugeColors.SubText, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

/** 数値入力。空欄は null。入力途中 ("-" や "1.") の間は値を確定しない */
@Composable
fun NumberField(
    label: String,
    value: Double?,
    placeholder: Double?,
    resetKey: Any,
    modifier: Modifier = Modifier,
    onChange: (Double?) -> Unit,
) {
    var text by remember(resetKey) { mutableStateOf(value?.let(::fmt).orEmpty()) }
    val invalid = text.isNotBlank() && text.toDoubleOrNull() == null
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() || it == '.' || it == '-' }
            when {
                text.isBlank() -> onChange(null)
                text.toDoubleOrNull() != null -> onChange(text.toDouble())
            }
        },
        label = { Text(label) },
        placeholder = { if (placeholder != null) Text(fmt(placeholder)) },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, name: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 6.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else GaugeColors.CardBorder, CircleShape)
            .clickable(onClickLabel = name, onClick = onClick),
    )
}

/**
 * 表示する項目を選ぶ。カスタム PID と標準項目を同じ一覧から選べる。
 */
@Composable
fun ItemPickerDialog(
    catalog: PidCatalog,
    supported: Set<Int>?,
    onPick: (PidDef) -> Unit,
    onCreateCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    fun matches(d: PidDef) = query.isBlank() ||
        d.name.contains(query, ignoreCase = true) || d.shortName.contains(query, ignoreCase = true) ||
        d.unit.contains(query, ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("表示する項目") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("絞り込み (例: 温度)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item { SectionHeader("カスタム PID") }
                    items(catalog.custom.filter(::matches), key = { it.key }) { d ->
                        PickerRow(d, unsupported = false) { onPick(d) }
                    }
                    item {
                        TextButton(onClick = onCreateCustom) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("カスタム PID を作成")
                        }
                        HorizontalDivider()
                    }
                    item { SectionHeader("標準の項目") }
                    items(com.obdmeter.core.Pids.ALL.filter(::matches), key = { it.key }) { d ->
                        PickerRow(d, isUnsupported(d, supported)) { onPick(d) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = GaugeColors.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun PickerRow(d: PidDef, unsupported: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(d.name, color = if (unsupported) GaugeColors.SubText else GaugeColors.Text)
        Text(
            "${d.sourceLabel()}  ${d.unit}" + if (unsupported) "  ・この車両は非対応" else "",
            color = GaugeColors.SubText, fontSize = 12.sp,
        )
    }
}
