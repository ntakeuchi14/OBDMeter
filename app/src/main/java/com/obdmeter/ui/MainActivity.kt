package com.obdmeter.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdmeter.session.ConnectionState
import com.obdmeter.ui.gauges.GaugeColors

enum class Screen { DASHBOARD, EDIT, SETTINGS, LOG, CUSTOM_PIDS }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                App(vm, onKeepScreenOn = { setKeepScreenOn(it) })
            }
        }
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

private val AppColors = darkColorScheme(
    primary = GaugeColors.Accent,
    onPrimary = GaugeColors.Background,
    secondary = GaugeColors.Warn,
    background = GaugeColors.Background,
    onBackground = GaugeColors.Text,
    surface = GaugeColors.Card,
    onSurface = GaugeColors.Text,
    surfaceVariant = GaugeColors.CardBorder,
    onSurfaceVariant = GaugeColors.SubText,
    error = GaugeColors.Warn,
)

@Composable
fun App(vm: MainViewModel, onKeepScreenOn: (Boolean) -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.DASHBOARD) }
    /** カスタム PID 画面を「新規作成」で開くか (項目選択から来たとき) */
    var customStartNew by rememberSaveable { mutableStateOf(false) }
    /** カスタム PID 画面から戻る先 */
    var customReturnTo by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()

    // 接続中だけ画面を消灯させない
    LaunchedEffect(settings.keepScreenOn, connection) {
        onKeepScreenOn(settings.keepScreenOn && connection !is ConnectionState.Disconnected)
    }

    val back: () -> Unit = {
        screen = if (screen == Screen.CUSTOM_PIDS) customReturnTo else Screen.DASHBOARD
    }
    BackHandler(enabled = screen != Screen.DASHBOARD) { back() }

    // 文字・アイコンの既定色を明るい色にする (指定しないと黒になり、暗い背景で見えない)
    CompositionLocalProvider(LocalContentColor provides GaugeColors.Text) {
    Box(
        Modifier
            .fillMaxSize()
            .background(GaugeColors.Background)
            .safeDrawingPadding()
    ) {
        when (screen) {
            Screen.DASHBOARD -> DashboardScreen(
                vm,
                onNavigate = { screen = it },
                onCreateCustomPid = {
                    customStartNew = true
                    customReturnTo = Screen.DASHBOARD
                    screen = Screen.CUSTOM_PIDS
                },
            )
            Screen.EDIT -> EditScreen(vm, onBack = back)
            Screen.SETTINGS -> SettingsScreen(
                vm,
                onBack = back,
                onOpenCustomPids = {
                    customStartNew = false
                    customReturnTo = Screen.SETTINGS
                    screen = Screen.CUSTOM_PIDS
                },
            )
            Screen.LOG -> LogScreen(vm, onBack = back)
            Screen.CUSTOM_PIDS -> CustomPidScreen(vm, startNew = customStartNew, onBack = back)
        }
    }
    }
}
