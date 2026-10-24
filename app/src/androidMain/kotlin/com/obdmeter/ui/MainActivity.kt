package com.obdmeter.ui

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obdmeter.AppCore
import com.obdmeter.get
import com.obdmeter.overlay.FloatController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
import com.obdmeter.ui.gauges.LocalFontLibrary
import com.obdmeter.ui.gauges.LocalHistory

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        androidx.lifecycle.viewmodel.viewModelFactory {
            androidx.lifecycle.viewmodel.initializer { MainViewModel(AppCore.get(application)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                App(vm, onKeepScreenOn = { setKeepScreenOn(it) })
            }
        }
        // フロート表示が ON の間は、アプリが前面にあるうちにサービスを起動しておく
        // (バックグラウンドからは起動できない端末があるため)。ウィンドウはアプリを離れたときだけ出る
        val core = AppCore.get(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                core.settings.map { it.floatOnLeave }.distinctUntilChanged().collect { on ->
                    if (on && FloatController.canDraw(this@MainActivity)) {
                        if (!core.floatActive.value) FloatController.start(this@MainActivity, null)
                    } else if (!on && core.floatActive.value) {
                        FloatController.stop(this@MainActivity)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 起動時にフロート表示で始める: 最初の 1 回だけ、サービスを起動してからホーム画面に戻る
        val core = AppCore.get(this)
        if (!core.startInFloatDone) {
            core.startInFloatDone = true
            lifecycleScope.launch {
                val s = core.repo.settings.first()
                if (s.floatOnLeave && s.startInFloat && FloatController.canDraw(this@MainActivity)) {
                    if (!core.floatActive.value) FloatController.start(this@MainActivity, null)
                    delay(400) // サービスの起動を待つ
                    moveTaskToBack(true)
                }
            }
        }
    }

    /**
     * iDrive などのダイヤル・方向キーでダッシュボードのページを切り替える。
     * 車載ナビによって、回転が左右/上下キー・PageUp/Down・ダイヤル (スクロール) のどれで来るかが違うので全部受ける
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val delta = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
                android.view.KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP, android.view.KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> -1
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_NAVIGATE_NEXT,
                android.view.KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN, android.view.KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> 1
                else -> 0
            }
            val name = android.view.KeyEvent.keyCodeToString(event.keyCode)
            if (vm.onNavInput(delta, "キー $name (${event.keyCode})")) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** ダイヤル (ロータリーエンコーダー) やマウスホイールの回転 */
    private var scrollAcc = 0f

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_SCROLL) {
            val scroll = ev.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)
            val vscroll = ev.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
            val hscroll = ev.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL)
            // ホイールは奥へ回すと + なので、下 (次のページ) を + にそろえる
            val v = when {
                scroll != 0f -> scroll
                vscroll != 0f -> -vscroll
                else -> hscroll
            }
            scrollAcc += v
            if (kotlin.math.abs(scrollAcc) >= 1f) {
                val delta = if (scrollAcc > 0) 1 else -1
                scrollAcc = 0f
                if (vm.onNavInput(delta, "ダイヤル/スクロール (source=${ev.source})")) return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        vm.setAppVisible(true)
    }

    override fun onStop() {
        vm.setAppVisible(false)
        super.onStop()
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}


