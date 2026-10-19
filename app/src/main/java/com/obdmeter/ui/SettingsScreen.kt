package com.obdmeter.ui

import android.widget.Toast
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material3.Slider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.obdmeter.overlay.FloatController
import kotlin.math.roundToInt
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
import androidx.compose.foundation.horizontalScroll
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

            Section("項目ごとの取得周期")
            PollIntervalSection(vm, s)

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

            Section("フロート表示")
            FloatSection(vm, s)

            Section("接続テスト")
            ConnectionTestSection(vm)

            Section("バックアップ")
            BackupSection(vm)

            Section("その他")
            SwitchRow(
                "iDrive・方向キーでページを切り替え",
                "車載ナビのダイヤルの回転・上下左右・PageUp/Down でダッシュボードのページを切り替えます。" +
                    "効かないときは、操作してから「ログ」画面の「入力: …」を送ってください",
                s.keyPageNav,
            ) { v -> vm.updateSettings { it.copy(keyPageNav = v) } }
            SwitchRow(
                "ダッシュボードのバーを常に表示",
                "OFF のときは、接続状態や編集・設定のボタンを隠してメーターを全画面に表示し、右上のボタンで呼び出します",
                s.alwaysShowToolbar,
            ) { v -> vm.updateSettings { it.copy(alwaysShowToolbar = v) } }
            SwitchRow(
                "起動時に自動接続",
                if (s.floatOnLeave) "フロート表示が ON のため、常に自動接続します"
                else "アプリを開いたら「接続」を押さなくても接続を始めます",
                s.autoConnectEffective,
                enabled = !s.floatOnLeave,
            ) { v -> vm.updateSettings { it.copy(autoConnect = v) } }
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
private fun SwitchRow(
    label: String,
    detail: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) GaugeColors.Text else GaugeColors.SubText)
            if (detail != null) Text(detail, color = GaugeColors.SubText, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** "21:00-FF, 22:0000-3FFF" を (サービス, 範囲) の一覧にする。読めない部分は飛ばす */
private fun parseSearchRanges(text: String): List<Pair<Int, IntRange>> =
    text.split(',', '、', ' ').mapNotNull { part ->
        val m = Regex("""^(21|22|30):([0-9A-F]{1,4})-([0-9A-F]{1,4})$""").find(part.trim()) ?: return@mapNotNull null
        val svc = m.groupValues[1].toInt(16)
        val a = m.groupValues[2].toInt(16)
        val b = m.groupValues[3].toInt(16)
        val max = if (svc == 0x22) 0xFFFF else 0xFF
        if (a > b || b > max) null else svc to (a..b)
    }

/**
 * フロート表示の設定。ON にすると、アプリを離れたときに表示中のページのメーターを
 * ほかのアプリやホーム画面の上に出す。「ほかのアプリの上に重ねて表示」の許可が必要。
 */
@Composable
private fun FloatSection(vm: MainViewModel, s: AppSettings) {
    val context = LocalContext.current
    var canDraw by remember { mutableStateOf(FloatController.canDraw(context)) }
    // 許可画面から戻ってきたら確認し直す
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canDraw = FloatController.canDraw(context) }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    SwitchRow(
        "メーターをフロート表示",
        "ホームボタンなどでアプリを離れると、表示していたページのメーターを 1 つずつ、ほかのアプリやホーム画面の上に小さく表示します。" +
            "ON のときは起動時の自動接続も ON になります",
        s.floatOnLeave,
    ) { v ->
        vm.updateSettings { it.copy(floatOnLeave = v, autoConnect = if (v) true else it.autoConnect) }
        if (v) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            if (!FloatController.canDraw(context)) {
                runCatching { context.startActivity(FloatController.permissionIntent(context)) }
            }
        }
    }
    if (s.floatOnLeave && !canDraw) {
        Text(
            "「ほかのアプリの上に重ねて表示」が許可されていないため、フロート表示できません",
            color = GaugeColors.Warn, fontSize = 12.sp,
        )
        OutlinedButton(onClick = { runCatching { context.startActivity(FloatController.permissionIntent(context)) } }) {
            Text("許可の画面を開く")
        }
    }
    if (s.floatOnLeave) {
        Text(
            "メーターの編集画面で「フロート表示のグループ」を同じにしたメーターは、1 つのウィンドウにまとめて表示します。" +
                "フロート表示の操作 (ウィンドウごと): ドラッグで移動、2 本指または右下の ◢ で大きさ変更、" +
                "タップで「開く」「✕」ボタン、ダブルタップでアプリを開く。位置と大きさはメーターごとに保存されます。" +
                "✕ で閉じたメーターは、次にアプリを離れたときにまた表示されます",
            color = GaugeColors.SubText, fontSize = 12.sp,
        )
        SwitchRow(
            "起動時にフロート表示で始める",
            "アプリを開くと自動で接続し、すぐにホーム画面に戻ってメーターをフロート表示します。" +
                "アプリの画面は、フロート表示の「開く」か通知から出せます",
            s.startInFloat,
        ) { v -> vm.updateSettings { it.copy(startInFloat = v) } }
        FloatSlider("不透明度", s.floatOpacity, 0.2f..1f) { v -> vm.updateSettings { it.copy(floatOpacity = v) } }
        FloatSlider("背景の濃さ", s.floatBackground, 0f..1f) { v -> vm.updateSettings { it.copy(floatBackground = v) } }
    }
}

@Composable
private fun FloatSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    // ドラッグ中は手元の値で動かし、離したときに保存する
    var v by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), fontSize = 14.sp)
        Slider(
            value = v, onValueChange = { v = it }, valueRange = range,
            onValueChangeFinished = { onChange((v * 20).roundToInt() / 20f) },
            modifier = Modifier.weight(1f),
        )
        Text("%.0f%%".format(v * 100), Modifier.width(48.dp), fontSize = 13.sp, color = GaugeColors.SubText)
    }
}


/**
 * 設定のエクスポート / インポート。
 * ダッシュボード (メーターの配置・色・角度・背景画像などすべて) とカスタム PID を 1 つの zip にまとめる。
 */
@Composable
private fun BackupSection(vm: MainViewModel) {
    val context = LocalContext.current
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
                val name = "OBDMeter_$stamp.zip"
                // 保存先を選ぶ画面が無い端末 (車載ナビなど) ではダウンロードフォルダに直接保存する
                if (runCatching { exportLauncher.launch(name) }.isFailure) vm.exportBackupToDownloads(name)
            },
        ) { Text("エクスポート") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            enabled = !busy,
            onClick = {
                val ok = runCatching {
                    importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }.isSuccess
                if (!ok) Toast.makeText(context, "この端末ではファイルを選ぶ画面を開けません", Toast.LENGTH_LONG).show()
            },
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

    // BimmerLink などに出ている値が、DME のどの ID にあるかを探す
    Text(
        "BMW の値を探す: BimmerLink などで見える値 (失火カウンタなど) を入力すると、DME の BMW 独自の値を順に読んで、" +
            "その値が入っている ID を探します (数分かかります。エンジン停止・イグニッション ON で実行してください)",
        color = GaugeColors.SubText, fontSize = 12.sp,
    )
    var targetText by remember { mutableStateOf("") }
    OutlinedTextField(
        value = targetText,
        onValueChange = { targetText = it },
        label = { Text("探す値 (カンマ区切り。例: 14,50,27,27,71,37)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val targets = targetText.split(',', ' ', '、').mapNotNull { it.trim().toDoubleOrNull()?.toLong() }
    var rangeText by remember { mutableStateOf("30:00-FF") }
    OutlinedTextField(
        value = rangeText,
        onValueChange = { rangeText = it.uppercase() },
        label = { Text("探す範囲 (21 / 22 / 30:開始-終了、16進。30 は「30 xx 01」で読む)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val ranges = parseSearchRanges(rangeText)
    FilledTonalButton(
        onClick = { vm.runBmwSearch(targets, ranges) },
        enabled = !running && targets.size >= 2 && ranges.isNotEmpty(),
        modifier = Modifier.padding(vertical = 8.dp),
    ) { Text("BMW の値を探す") }

    lines?.let { list ->
        AlertDialog(
            onDismissRequest = vm::closeConnectionTest,
            title = { Text(if (running) "実行中…" else "結果") },
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
                    if (running) TextButton(onClick = vm::stopTest) { Text("中止") }
                    TextButton(enabled = !running, onClick = vm::closeConnectionTest) { Text("閉じる") }
                }
            },
        )
    }
}

/**
 * ページで使っている項目ごとに、車から読む周期を選ぶ。変化が遅い項目 (水温・油温・電圧など) の周期を
 * 長くすると、そのぶん回転数などの速い項目の更新が速くなる
 */
@Composable
private fun PollIntervalSection(vm: MainViewModel, s: AppSettings) {
    val dashboards by vm.dashboards.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Text(
        "変化が遅い項目 (水温・油温・電圧など) は周期を長くすると、回転数などの速い項目の更新が速くなります。" +
            "「毎回」は表示中は毎サイクル読みます。設定はその項目を使うすべてのメーター・グラフに共通です。",
        color = GaugeColors.SubText, fontSize = 12.sp,
    )
    val keys = remember(dashboards) {
        dashboards.flatMap { it.gauges }.flatMap { g -> com.obdmeter.data.ReadPlan.gaugeKeys(g) + listOfNotNull(g.fallbackKey) }.distinct()
    }
    val changed = s.pollIntervalsMs.count { it.value > 0 }
    TextButton(onClick = { open = !open }) {
        Text(if (open) "閉じる" else "項目の一覧を開く (ページで使用中 ${keys.size} 項目、周期を変えたもの $changed 項目)")
    }
    if (!open) return
    // ページで使っていない項目も、名前で探して設定できる (先に設定しておける)
    var query by remember { mutableStateOf("") }
    androidx.compose.material3.OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        placeholder = { Text("ほかの項目を探す (例: ブースト、水温、5A2C)") },
        modifier = Modifier.fillMaxWidth(),
    )
    val shownKeys = if (query.isBlank()) {
        // 使用中の項目 + 周期を変えてある項目
        (keys + s.pollIntervalsMs.keys).distinct()
    } else {
        val q = query.trim()
        catalog.all.filter { it.name.contains(q, ignoreCase = true) || it.shortName.contains(q, ignoreCase = true) ||
            it.key.contains(q, ignoreCase = true) }.map { it.key }.take(40)
    }
    if (query.isNotBlank() && shownKeys.isEmpty()) Text("見つかりません", color = GaugeColors.SubText, fontSize = 12.sp)
    val options = listOf(0 to "毎回", 500 to "0.5秒", 1000 to "1秒", 2000 to "2秒", 5000 to "5秒", 10000 to "10秒", 30000 to "30秒")
    shownKeys.forEach { key ->
        val def = catalog.get(key)
        val cur = s.pollIntervalsMs[key] ?: 0
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(def?.name ?: key, fontSize = 14.sp)
            if (def?.slow == true && cur == 0) Text("既定では 8 サイクルに 1 回読みます", color = GaugeColors.SubText, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                options.forEach { (ms, label) ->
                    FilterChip(cur == ms, {
                        vm.updateSettings { st ->
                            st.copy(pollIntervalsMs = if (ms == 0) st.pollIntervalsMs - key else st.pollIntervalsMs + (key to ms))
                        }
                    }, { Text(label) }, Modifier.padding(end = 6.dp))
                }
            }
        }
    }
}

