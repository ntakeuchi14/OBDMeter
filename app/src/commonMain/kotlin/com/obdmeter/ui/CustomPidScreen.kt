package com.obdmeter.ui

import com.obdmeter.util.format

import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdmeter.core.Hex
import com.obdmeter.data.AppSettings
import com.obdmeter.data.CustomPid
import com.obdmeter.data.UsbAdapter
import com.obdmeter.ui.gauges.GaugeColors
import kotlinx.coroutines.launch

/**
 * カスタム PID の一覧と編集。
 * [startNew] が true なら、開いた直後に新規作成の編集画面を出す (項目選択の「カスタム PID を作成」から来たとき)。
 */
@Composable
fun CustomPidScreen(vm: MainViewModel, startNew: Boolean, onBack: () -> Unit) {
    val list by vm.customPids.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingId by rememberSaveable { mutableStateOf(if (startNew) NEW else NONE) }
    /** 複製して新規作成するときの元 */
    var copyFrom by remember { mutableStateOf<CustomPid?>(null) }
    var deleting by remember { mutableStateOf<CustomPid?>(null) }

    val editing = when (editingId) {
        NONE -> null
        NEW -> copyFrom?.copy(id = 0, name = copyFrom!!.name + " のコピー") ?: newPid()
        else -> list.firstOrNull { it.id == editingId }
    }

    if (editing != null) {
        CustomPidEditor(
            initial = editing,
            isNew = editingId == NEW,
            settings = settings,
            vm = vm,
            onSave = { vm.saveCustomPid(it); editingId = NONE; copyFrom = null },
            onDelete = { vm.deleteCustomPid(it.id); editingId = NONE },
            onCancel = { if (startNew && editingId == NEW) onBack() else { editingId = NONE; copyFrom = null } },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("カスタム PID", onBack)
        Text(
            "標準にない値 (メーカー独自の値など) を、モード・PID と換算式を指定して読み出します。" +
                "登録した項目は、メーター追加の一覧に「カスタム PID」として出てきます。" +
                "各行の右のボタンで編集・複製・削除できます。",
            color = GaugeColors.SubText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            items(list, key = { it.id }) { p ->
                val error = p.validate()
                Row(
                    Modifier.fillMaxWidth().clickable { copyFrom = null; editingId = p.id }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name.ifBlank { "(名前なし)" }, fontWeight = FontWeight.Medium)
                        Text(
                            "Mode ${p.mode}  PID ${p.pid}" + (if (p.header.isNotBlank()) "  @${p.header}" else "") +
                                "   式: ${p.formula}   ${p.unit}",
                            color = GaugeColors.SubText, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        )
                        if (error != null) Text("⚠ $error", color = GaugeColors.Warn, fontSize = 12.sp)
                    }
                    IconButton(onClick = { copyFrom = null; editingId = p.id }) { Icon(Icons.Default.Edit, "編集") }
                    TextButton(onClick = { copyFrom = p; editingId = NEW }) { Text("複製") }
                    IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, "削除", tint = GaugeColors.Warn) }
                }
                HorizontalDivider()
            }
            if (list.isEmpty()) {
                item {
                    Text("まだ登録されていません", color = GaugeColors.SubText,
                        modifier = Modifier.padding(16.dp))
                }
            }
            item {
                TextButton(onClick = { copyFrom = null; editingId = NEW }, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("カスタム PID を追加")
                }
            }
        }
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("「${p.name.ifBlank { "(名前なし)" }}」を削除しますか？") },
            text = { Text("この項目を表示しているメーターも削除されます。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteCustomPid(p.id); deleting = null }) { Text("削除", color = GaugeColors.Warn) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } },
        )
    }
}

private const val NONE = -1L
private const val NEW = -2L

private fun newPid() = CustomPid(id = 0, name = "", mode = "22", pid = "", formula = "A")

@Composable
private fun CustomPidEditor(
    initial: CustomPid,
    isNew: Boolean,
    settings: AppSettings,
    vm: MainViewModel,
    onSave: (CustomPid) -> Unit,
    onDelete: (CustomPid) -> Unit,
    onCancel: () -> Unit,
) {
    // 入力途中の文字列はそのまま保持し、保存時にまとめて検証する
    var name by remember { mutableStateOf(initial.name) }
    var shortName by remember { mutableStateOf(initial.shortName) }
    var unit by remember { mutableStateOf(initial.unit) }
    var mode by remember { mutableStateOf(initial.mode) }
    var pid by remember { mutableStateOf(initial.pid) }
    var header by remember { mutableStateOf(initial.header) }
    var cea by remember { mutableStateOf(initial.canExtAddress) }
    var formula by remember { mutableStateOf(initial.formula) }
    var min by remember { mutableStateOf(fmtNum(initial.min)) }
    var max by remember { mutableStateOf(fmtNum(initial.max)) }
    var decimals by remember { mutableStateOf(initial.decimals) }
    var warnAbove by remember { mutableStateOf(initial.warnAbove?.let(::fmtNum).orEmpty()) }
    var warnBelow by remember { mutableStateOf(initial.warnBelow?.let(::fmtNum).orEmpty()) }
    var slow by remember { mutableStateOf(initial.slow) }

    var sample by remember { mutableStateOf("") }
    var liveResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun build(): CustomPid = initial.copy(
        id = if (isNew) com.obdmeter.util.nowMs() else initial.id,
        name = name.trim(), shortName = shortName.trim(), unit = unit.trim(),
        mode = mode.trim().uppercase(), pid = pid.trim().uppercase().replace(" ", ""),
        header = header.trim().uppercase(), canExtAddress = cea.trim().uppercase(),
        formula = formula.trim(),
        min = min.toDoubleOrNull() ?: 0.0, max = max.toDoubleOrNull() ?: 100.0, decimals = decimals,
        warnAbove = warnAbove.toDoubleOrNull(), warnBelow = warnBelow.toDoubleOrNull(), slow = slow,
    )

    val candidate = build()
    val nameError = if (name.isBlank()) "名前を入力してください" else null
    val numberError = when {
        min.toDoubleOrNull() == null -> "最小値が数値ではありません"
        max.toDoubleOrNull() == null -> "最大値が数値ではありません"
        warnAbove.isNotBlank() && warnAbove.toDoubleOrNull() == null -> "警告上限が数値ではありません"
        warnBelow.isNotBlank() && warnBelow.toDoubleOrNull() == null -> "警告下限が数値ではありません"
        else -> null
    }
    val formError = nameError ?: numberError ?: candidate.validate()

    // 例として入力したバイト列で式を試す
    val sampleResult: String? = if (sample.isBlank()) null else runCatching {
        val bytes = Hex.parse(sample)
        require(bytes.isNotEmpty()) { "16 進数で入力してください (例: 1A F8)" }
        val v = candidate.copy(name = candidate.name.ifBlank { "x" }).toDef().decode(bytes)
        "= " + "%.${decimals}f".format(v) + " " + unit
    }.getOrElse { "⚠ " + it.message }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (isNew) "カスタム PID を追加" else "カスタム PID を編集", onCancel)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("名前")
            Field("名前 (例: 油温 DME)", name, { name = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("メーターの表示名 (短く)", shortName, { shortName = it }, Modifier.weight(1f))
                Field("単位", unit, { unit = it }, Modifier.weight(0.6f))
            }

            Section("問い合わせ")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("モード (16進)", mode, { mode = it }, Modifier.weight(0.6f), hex = true)
                Field("PID (16進)", pid, { pid = it }, Modifier.weight(1f), hex = true)
            }
            val headerHelp = if (settings.usbAdapter == UsbAdapter.DCAN) {
                "K+DCAN: 送信先 ECU アドレス (例 12 = DME、18 = EGS)。空欄で設定画面の値"
            } else {
                "OBDLink: 送信ヘッダー ATSH (例 7E0 = エンジン ECU 直接)。空欄で標準の 7DF"
            }
            Field("ヘッダー (任意)", header, { header = it }, hex = true)
            Hint(headerHelp)
            if (settings.usbAdapter == UsbAdapter.OBDLINK) {
                Field("CAN 拡張アドレス (任意、ATCEA)", cea, { cea = it }, hex = true)
                Hint("BMW のように ID 6F1 + 拡張アドレスで通信する ECU 用。通常は空欄")
            }

            Section("換算式")
            Field("式", formula, { formula = it }, mono = true)
            Hint(
                "応答データの 1 バイト目が A、2 バイト目が B … です (サービス ID と PID のエコーは除いた後)。\n" +
                    "例: (A*256+B)/4   A-40   signed(A)*0.5   signed16(A*256+B)/10   bit(A,3)\n" +
                    "使える関数: abs min max round floor signed signed16 bit"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("試しに計算 (例: 1A F8)", sample, { sample = it }, Modifier.weight(1f), hex = true, mono = true)
            }
            if (sampleResult != null) {
                Text(sampleResult, fontFamily = FontFamily.Monospace,
                    color = if (sampleResult.startsWith("⚠")) GaugeColors.Warn else GaugeColors.Accent)
            }

            Section("メーター")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("最小", min, { min = it }, Modifier.weight(1f), number = true)
                Field("最大", max, { max = it }, Modifier.weight(1f), number = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("警告 下限 (任意)", warnBelow, { warnBelow = it }, Modifier.weight(1f), number = true)
                Field("警告 上限 (任意)", warnAbove, { warnAbove = it }, Modifier.weight(1f), number = true)
            }
            Text("小数点以下の桁数", color = GaugeColors.SubText, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row {
                (0..3).forEach { n ->
                    FilterChip(decimals == n, { decimals = n }, { Text("$n") }, Modifier.padding(end = 6.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("変化が遅い値")
                    Hint("温度など。読む頻度を下げて、他のメーターの更新を速くします")
                }
                Switch(slow, { slow = it })
            }

            Section("車両から読んでみる")
            Hint("ダッシュボードで接続中なら、今の内容で 1 回だけ問い合わせます。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    enabled = !testing && candidate.validate() == null,
                    onClick = {
                        testing = true
                        scope.launch {
                            val r = vm.testCustomPid(candidate)
                            liveResult = if (r.data != null) {
                                val value = runCatching {
                                    "%.${decimals}f %s".format(candidate.toDef().decode(r.data), unit)
                                }.getOrElse { "⚠ ${it.message}" }
                                "応答データ: ${Hex.of(r.data).ifEmpty { "(なし)" }}\n値: $value"
                            } else {
                                "⚠ ${r.error}"
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "問い合わせ中…" else "車両から読む") }
            }
            liveResult?.let {
                Text(
                    it, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    color = if (it.startsWith("⚠")) GaugeColors.Warn else GaugeColors.Text,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .background(GaugeColors.Card, RoundedCornerShape(8.dp)).padding(10.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        // ---- 下部ボタン ----
        HorizontalDivider()
        if (formError != null) {
            Text("⚠ $formError", color = GaugeColors.Warn, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isNew) OutlinedButton(onClick = { confirmDelete = true }) { Text("削除", color = GaugeColors.Warn) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCancel) { Text("キャンセル") }
            Button(onClick = { onSave(candidate) }, enabled = formError == null) { Text("保存") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("「${initial.name}」を削除しますか？") },
            text = { Text("この項目を表示しているメーターも削除されます。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(initial) }) { Text("削除", color = GaugeColors.Warn) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
}

private fun fmtNum(v: Double): String = if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

@Composable
private fun Section(title: String) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GaugeColors.Accent,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = GaugeColors.SubText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    hex: Boolean = false,
    number: Boolean = false,
    mono: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { t ->
            onChange(
                when {
                    hex -> t.uppercase().filter { it.isDigit() || it in 'A'..'F' || it == ' ' }
                    number -> t.filter { it.isDigit() || it == '.' || it == '-' }
                    else -> t
                }
            )
        },
        label = { Text(label) },
        singleLine = true,
        textStyle = if (mono) androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace) else androidx.compose.ui.text.TextStyle.Default,
        keyboardOptions = when {
            hex -> KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false)
            number -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
            else -> KeyboardOptions.Default
        },
        modifier = modifier.padding(vertical = 2.dp),
    )
}
