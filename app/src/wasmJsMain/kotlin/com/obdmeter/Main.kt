package com.obdmeter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import com.obdmeter.ui.App
import com.obdmeter.ui.AppColors
import com.obdmeter.ui.MainViewModel
import com.obdmeter.ui.gauges.GaugeColors
import com.obdmeter.web.WebPlatform
import com.obdmeter.web.WebUi
import com.obdmeter.web.fetchBinary
import kotlinx.browser.document
import kotlinx.coroutines.delay

private fun hideLoading(): Unit = js("{ var e = document.getElementById('loading'); if (e) e.remove(); }")

private fun jsRethrow(e: JsAny): Unit = js("{ throw e; }")

private fun jsOnUnhandled(handler: (JsAny) -> Unit): Unit = js(
    "{ window.addEventListener('unhandledrejection', function (ev) { if (ev.reason) handler(ev.reason); });" +
        " window.addEventListener('error', function (ev) { if (ev.error) handler(ev.error); }); }"
)

private var reported = 0

/** 捕まえられなかった例外の中身をコンソールに出す (Kotlin の例外は JS からは中身が見えないため) */
private fun report(e: JsAny) {
    if (++reported > 5) return
    try {
        jsRethrow(e)
    } catch (t: Throwable) {
        println("未処理の例外: " + t.stackTraceToString())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    jsOnUnhandled { report(it) }
    try {
        start()
    } catch (e: Throwable) {
        println("起動に失敗しました: " + e.stackTraceToString())
        throw e
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun start() {
    println("OBD Meter Web: 起動")
    val platform = WebPlatform()
    WebUi.platform = platform
    val core = AppCore(platform)
    println("OBD Meter Web: AppCore OK")
    ComposeViewport(document.body!!) {
        var fontsReady by remember { mutableStateOf(false) }
        val resolver = LocalFontFamilyResolver.current
        LaunchedEffect(Unit) {
            // 日本語のフォント (Web では端末のフォントを使えないので、サイトに置いたものを読み込んでおく)
            // 先に読み込んだものから使う: 英数字・記号は Android と同じ Roboto、日本語は Noto Sans JP
            val roboto = listOf(
                "fonts/Roboto-Regular.ttf" to androidx.compose.ui.text.font.FontWeight.Normal,
                "fonts/Roboto-Medium.ttf" to androidx.compose.ui.text.font.FontWeight.Medium,
                "fonts/Roboto-Bold.ttf" to androidx.compose.ui.text.font.FontWeight.Bold,
            ).mapNotNull { (file, weight) ->
                fetchBinary(file)?.let { androidx.compose.ui.text.platform.Font(file, it, weight) }
            }
            if (roboto.isNotEmpty()) runCatching { resolver.preload(FontFamily(roboto)) }
            for (file in listOf("fonts/NotoSansJP-Regular.otf", "fonts/NotoSansMono-Regular.ttf")) {
                val bytes = fetchBinary(file) ?: continue
                runCatching {
                    resolver.preload(FontFamily(androidx.compose.ui.text.platform.Font(file, bytes)))
                }
            }
            println("OBD Meter Web: フォント OK")
            fontsReady = true
            hideLoading()
        }
        MaterialTheme(colorScheme = AppColors) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF05070A)).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && WebUi.backHandlers.isNotEmpty()) {
                        WebUi.backHandlers.last()()
                        true
                    } else false
                }
            ) {
                if (fontsReady) {
                    val vm = remember { println("OBD Meter Web: 画面"); MainViewModel(core) }
                    App(vm, onKeepScreenOn = {})
                }
                Toast(Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun Toast(modifier: Modifier) {
    val text by WebUi.message.collectAsState()
    val seq by WebUi.messageSeq.collectAsState()
    LaunchedEffect(seq) {
        if (text != null) {
            delay(4000)
            WebUi.message.value = null
        }
    }
    text?.let {
        Text(
            it,
            modifier.padding(24.dp).background(Color(0xE0303030), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
            color = Color.White,
            fontSize = 14.sp,
        )
    }
}
