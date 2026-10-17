package com.obdmeter.ui

import android.content.Intent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import com.obdmeter.data.ImportMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdmeter.data.AppSettings
import com.obdmeter.data.ConnectionType
import com.obdmeter.data.UsbAdapter
import com.obdmeter.session.ConnectionState
import com.obdmeter.ui.gauges.GaugeColors

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onOpenCustomPids: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopBar("設定", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            if (connection !is ConnectionState.Disconnected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("変更は次の接続から反映されます", color = GaugeColors.SubText, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = vm::connect) { Text("再接続") }
                }
                Spacer(Modifier.height(8.dp))
            }

            Section("表示項目")
            val customCount = vm.customPids.collectAsStateWithLifecycle().value.size
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenCustomPids).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("カスタム PID")
                    Text("標準にない値を、モード・PID と換算式で登録 (登録済み $customCount 件)",
                        color = GaugeColors.SubText, fontSize = 12.sp)
                }
                Text("›", fontSize = 22.sp, color = GaugeColors.SubText)
            }

            Section("接続方式")
            ConnectionType.entries.forEach { t ->
                RadioRow(t.label, null, s.connectionType == t) { vm.updateSettings { it.copy(connectionType = t) } }
            }

            Section("USB アダプター")
            UsbAdapter.entries.forEach { a ->
                RadioRow(a.label, a.detail, s.usbAdapter == a, enabled = s.connectionType == ConnectionType.USB) {
                    vm.updateSettings { it.copy(usbAdapter = a) }
                }
            }

            if (s.usbAdapter == UsbAdapter.OBDLINK) {
                Section("OBDLink / ELM327 詳細")
                Label("ボーレート (OBDLink SX/EX の初期値は 115200)")
                Row {
                    AppSettings.ELM_BAUD_RATES.forEach { b ->
                        FilterChip(
                            selected = s.elmBaudRate == b,
                            onClick = { vm.updateSettings { it.copy(elmBaudRate = b) } },
                            label = { Text(if (b >= 1000) "${b / 1000}k" else "$b") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Label("通信プロトコル")
                AppSettings.ELM_PROTOCOLS.forEach { (code, label) ->
                    RadioRow(label, "ATSP$code", s.elmProtocol == code) {
                        vm.updateSettings { it.copy(elmProtocol = code) }
                    }
                }
                SwitchRow(
                    "高速応答モード",
                    "要求に応答数を付けて待ち時間を減らす。値が出ない場合は OFF に",
                    s.elmSingleResponseHint,
                ) { v -> vm.updateSettings { it.copy(elmSingleResponseHint = v) } }
            } else {
                Section("K+DCAN ケーブル詳細")
                Text(
                    "ケーブルのスイッチ: 2007/03 以降の E90 は D-CAN 側、それ以前は K-Line 側にしてください。" +
                        "通信は 115200bps 固定です。",
                    color = GaugeColors.SubText, fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                var addrText by remember(s.dcanTargetAddress) { mutableStateOf("%02X".format(s.dcanTargetAddress)) }
                OutlinedTextField(
                    value = addrText,
                    onValueChange = { t ->
                        addrText = t.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(2)
                        // 2 桁そろったら保存 (1 桁入力中に値が書き換わらないように)
                        if (addrText.length == 2) {
                            addrText.toIntOrNull(16)?.let { v -> vm.updateSettings { it.copy(dcanTargetAddress = v) } }
                        }
                    },
                    label = { Text("問い合わせ先 ECU アドレス (16進, DME = 12)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("ケーブルのエコーを除去", "通常の K+DCAN ケーブルは ON", s.dcanEcho) { v ->
                    vm.updateSettings { it.copy(dcanEcho = v) }
                }
            }

            Section("接続テスト")
            ConnectionTestSection(vm)

            Section("バックアップ")
            BackupSection(vm)

            Section("その他")
            SwitchRow(
                "ダッシュボードのバーを常に表示",
                "OFF のときは、接続状態や編集・設定のボタンを隠してメーターを全画面に表示し、右上のボタンで呼び出します",
                s.alwaysShowToolbar,
            ) { v -> vm.updateSettings { it.copy(alwaysShowToolbar = v) } }
            SwitchRow("起動時に自動接続", "アプリを開いたら「接続」を押さなくても接続を始めます", s.autoConnect) { v ->
                vm.updateSettings { it.copy(autoConnect = v) }
            }
            SwitchRow("自動再接続", "通信が切れたら 3 秒後に再接続", s.autoReconnect) { v ->
                vm.updateSettings { it.copy(autoReconnect = v) }
            }
            SwitchRow(
                "イグニッション OFF でも接続して待機",
                "アダプターに接続したまま 5 秒ごとに車両を確認し、イグニッション ON で自動的に表示を始めます。" +
                    "待機中もバッテリー電圧は表示されます",
                s.waitForVehicle,
            ) { v -> vm.updateSettings { it.copy(waitForVehicle = v) } }
            if (s.waitForVehicle) {
                Text("待機の上限", color = GaugeColors.SubText, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Row {
                    listOf(5, 15, 30, 60, 0).forEach { m ->
                        FilterChip(
                            selected = s.waitTimeoutMin == m,
                            onClick = { vm.updateSettings { it.copy(waitTimeoutMin = m) } },
                            label = { Text(if (m == 0) "無制限" else "${m}分") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Text(
                    "待機中は車両に問い合わせを送るため、車両側の機器が起きたままになることがあります。" +
                        "長時間つないだまま放置すると、バッテリーが減る可能性があります。",
                    color = GaugeColors.SubText, fontSize = 12.sp,
                )
            }
            SwitchRow("接続中は画面を消灯しない", null, s.keepScreenOn) { v ->
                vm.updateSettings { it.copy(keepScreenOn = v) }
            }
            Spacer(Modifier.height(16.dp))
            val context = LocalContext.current
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
            }
            Text("バージョン $version", color = GaugeColors.SubText, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GaugeColors.Accent)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, color = GaugeColors.SubText, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun RadioRow(label: String, detail: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(label, color = if (enabled) GaugeColors.Text else GaugeColors.SubText)
            if (detail != null) Text(detail, color = GaugeColors.SubText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SwitchRow(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (detail != null) Text(detail, color = GaugeColors.SubText, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


/**
 * 設定のエクスポート / インポート。
 * ダッシュボード (メーターの配置・色・角度・背景画像などすべて) とカスタム PID を 1 つの zip にまとめる。
 */
@Composable
private fun BackupSection(vm: MainViewModel) {
    val busy by vm.backupBusy.collectAsStateWithLifecycle()
    val message by vm.backupMessage.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadBackup(uri)
    }

    Text(
        "ダッシュボード (メーターの配置・色・角度・背景画像など)、カスタム PID、取り込んだフォントを 1 つのファイル (zip) に保存します。" +
            "機種変更や再インストールのときに、画像も含めてそのまま戻せます。",
        color = GaugeColors.SubText, fontSize = 12.sp,
    )
    Row(Modifier.padding(vertical = 8.dp)) {
        FilledTonalButton(
            enabled = !busy,
            onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(Date())
                exportLauncher.launch("OBDMeter_$stamp.zip")
            },
        ) { Text("エクスポート") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            enabled = !busy,
            onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
        ) { Text("インポート") }
        if (busy) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
        }
    }

    pending?.let { b ->
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text("設定ファイルを読み込みます") },
            text = {
                Column {
                    Text(
                        "ページ ${b.pageCount} (メーター ${b.gaugeCount})\n" +
                            "カスタム PID ${b.customPidCount}\n" +
                            "背景画像 ${b.imageCount}\n" +
                            "フォント ${b.fontCount}" +
                            (if (b.manifest.exportedAt.isNotBlank()) "\n作成日時 ${b.manifest.exportedAt}" else "") +
                            (if (b.manifest.appVersion.isNotBlank()) "  (バージョン ${b.manifest.appVersion})" else ""),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("置き換え: 今のページとカスタム PID を消して、ファイルの内容にします。", fontSize = 13.sp, color = GaugeColors.SubText)
                    Text("追加: 今の内容は残したまま、ファイルのページとカスタム PID を追加します。", fontSize = 13.sp, color = GaugeColors.SubText)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.applyImport(ImportMode.MERGE) }) { Text("追加") }
                    TextButton(onClick = { vm.applyImport(ImportMode.REPLACE) }) { Text("置き換え", color = GaugeColors.Warn) }
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text("キャンセル") } },
        )
    }

    message?.let { m ->
        AlertDialog(
            onDismissRequest = vm::clearBackupMessage,
            text = { Text(m) },
            confirmButton = { TextButton(onClick = vm::clearBackupMessage) { Text("OK") } },
        )
    }
}


/** うまくつながらないときの接続テスト。結果は共有ボタンでテキストとして送れる */
@Composable
private fun ConnectionTestSection(vm: MainViewModel) {
    val lines by vm.testLines.collectAsStateWithLifecycle()
    val running by vm.testRunning.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Text(
        "アダプター・ケーブル・車両のどこで止まっているかを調べます。接続中の場合はいったん切断します。" +
            "結果を共有して送ってもらえれば、原因を特定できます。",
        color = GaugeColors.SubText, fontSize = 12.sp,
    )
    FilledTonalButton(onClick = vm::runConnectionTest, enabled = !running, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(if (running) "テスト中…" else "接続テストを実行")
    }

    lines?.let { list ->
        AlertDialog(
            onDismissRequest = vm::closeConnectionTest,
            title = { Text(if (running) "接続テスト中…" else "接続テストの結果") },
            text = {
                val scroll = rememberScrollState()
                LaunchedEffect(list.size) { scroll.animateScrollTo(scroll.maxValue) }
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(scroll)) {
                    list.forEach { l ->
                        Text(
                            l, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = when {
                                l.startsWith("✗") -> GaugeColors.Warn
                                l.startsWith("○") -> Color(0xFF81C784)
                                l.startsWith("---") || l.startsWith("==") -> GaugeColors.Accent
                                else -> GaugeColors.Text
                            },
                        )
                    }
                    if (running) CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = !running, onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "OBD Meter 接続テスト")
                            putExtra(Intent.EXTRA_TEXT, list.joinToString("\n"))
                        }
                        context.startActivity(Intent.createChooser(send, "結果を共有"))
                    }) { Text("共有") }
                    TextButton(enabled = !running, onClick = vm::closeConnectionTest) { Text("閉じる") }
                }
            },
        )
    }
}
