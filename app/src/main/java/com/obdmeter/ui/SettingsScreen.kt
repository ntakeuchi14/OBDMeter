package com.obdmeter.ui

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

            Section("その他")
            SwitchRow("自動再接続", "通信が切れたら 3 秒後に再接続", s.autoReconnect) { v ->
                vm.updateSettings { it.copy(autoReconnect = v) }
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
