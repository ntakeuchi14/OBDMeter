package com.obdmeter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import com.obdmeter.data.FontEntry
import com.obdmeter.web.WebUi
import com.obdmeter.web.downloadFile
import com.obdmeter.web.pickFile

actual val isWebApp: Boolean = true

@Composable
actual fun rememberToast(): (String) -> Unit = remember { { msg: String -> WebUi.toast(msg) } }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val latest by rememberUpdatedState(onBack)
    androidx.compose.runtime.DisposableEffect(enabled) {
        val handler: () -> Unit = { latest() }
        if (enabled) WebUi.backHandlers.add(handler)
        onDispose { WebUi.backHandlers.remove(handler) }
    }
}

@Composable
actual fun rememberFileOpener(mimeTypes: List<String>, onPicked: (PickedFile) -> Unit): () -> Boolean {
    val callback by rememberUpdatedState(onPicked)
    val accept = remember(mimeTypes) {
        when {
            mimeTypes.all { it.startsWith("image/") } -> "image/*"
            mimeTypes.any { it.contains("zip") } -> ".zip,application/zip"
            mimeTypes.any { it.startsWith("font/") } -> ".ttf,.otf"
            mimeTypes.any { it.startsWith("text/") } -> ".csv,.txt,text/*"
            else -> ""
        }
    }
    return remember(accept) {
        {
            pickFile(accept) { f -> callback(PickedFile(f.name) { f.read() }) }
            true
        }
    }
}

@Composable
actual fun rememberFileSaver(mimeType: String, onTarget: (FileTarget) -> Unit): (String) -> Unit {
    val callback by rememberUpdatedState(onTarget)
    return remember(mimeType) {
        { name: String ->
            callback(FileTarget { bytes ->
                downloadFile(name, bytes, mimeType)
                "ダウンロード ($name)"
            })
        }
    }
}

@Composable
actual fun rememberShareText(): (subject: String, text: String) -> Unit = remember {
    { subject: String, text: String ->
        val name = subject.replace(' ', '_') + ".txt"
        downloadFile(name, text.encodeToByteArray(), "text/plain")
    }
}

@Composable
actual fun rememberGaugeImage(name: String?): ImageBitmap? {
    val platform = WebUi.platform
    return produceState<ImageBitmap?>(null, name) {
        value = name?.let { platform.loadImage(it) }
    }.value
}

@Composable
actual fun rememberFontFamily(entry: FontEntry?): FontFamily? {
    val platform = WebUi.platform
    return produceState<FontFamily?>(
        entry?.let { e -> platform.fontData[e.file]?.let { family(e, it) } },
        entry?.id, entry?.file,
    ) {
        value = entry?.let { e -> platform.loadFont(e)?.let { family(e, it) } }
    }.value
}

private val families = HashMap<String, FontFamily>()

private fun family(entry: FontEntry, bytes: ByteArray): FontFamily? = families[entry.file] ?: runCatching {
    FontFamily(androidx.compose.ui.text.platform.Font(entry.file, bytes))
}.getOrNull()?.also { families[entry.file] = it }

actual fun DrawScope.drawSoftShadow(path: Path, dx: Float, dy: Float, blur: Float, alpha: Float) {
    if (alpha <= 0f) return
    drawIntoCanvas { canvas ->
        val paint = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true
            color = org.jetbrains.skia.Color.makeARGB((alpha.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)
            if (blur > 0.5f) maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, blur * 0.57735f + 0.5f)
        }
        val c = canvas.nativeCanvas
        c.save()
        c.translate(dx, dy)
        c.drawPath(path.asSkiaPath(), paint)
        c.restore()
    }
}

@Composable
actual fun rememberFloatPermission(): FloatPermissionState = remember { FloatPermissionState(false, {}, {}) }

private val PRESETS = listOf(
    Triple(1920, 720, 1f), Triple(1280, 480, 1f), Triple(1280, 720, 1f),
    Triple(1920, 1080, 1.5f), Triple(1024, 600, 1f), Triple(2400, 1080, 2.625f),
)

private fun fmtDensity(d: Float): String {
    val t = (kotlin.math.round(d * 1000) / 1000.0).toString()
    return if (t.contains('.')) t.trimEnd('0').trimEnd('.') else t
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
actual fun ScreenSizeSection() {
    val current by WebUi.screen.collectAsState()
    var wText by androidx.compose.runtime.remember(current) { androidx.compose.runtime.mutableStateOf(current?.width?.toString() ?: "1920") }
    var hText by androidx.compose.runtime.remember(current) { androidx.compose.runtime.mutableStateOf(current?.height?.toString() ?: "720") }
    var dText by androidx.compose.runtime.remember(current) { androidx.compose.runtime.mutableStateOf(current?.density?.let { fmtDensity(it) } ?: "1") }
    val sub = com.obdmeter.ui.gauges.GaugeColors.SubText
    androidx.compose.material3.Text(
        "編集する画面の大きさ (px) と倍率を指定します。ブラウザーのウィンドウより大きいときは、縮小して全体を表示します。" +
            "Android 版の 設定 → 画面サイズ に、その端末の値が出ています。",
        color = sub, fontSize = 12.sp,
    )
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 6.dp)) {
        androidx.compose.material3.FilterChip(
            current == null, { WebUi.setScreen(null) }, { androidx.compose.material3.Text("ウィンドウに合わせる") },
            Modifier.padding(end = 6.dp),
        )
        for ((w, h, d) in PRESETS) {
            val sel = current?.let { it.width == w && it.height == h && it.density == d } == true
            androidx.compose.material3.FilterChip(
                sel, { WebUi.setScreen(com.obdmeter.web.ScreenSize(w, h, d)) },
                { androidx.compose.material3.Text("$w×$h" + if (d != 1f) " (×${fmtDensity(d)})" else "") },
                Modifier.padding(end = 6.dp),
            )
        }
    }
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        val num = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
        androidx.compose.material3.OutlinedTextField(wText, { wText = it.filter(Char::isDigit).take(4) }, Modifier.width(110.dp),
            label = { androidx.compose.material3.Text("幅 px") }, singleLine = true, keyboardOptions = num)
        androidx.compose.material3.Text(" × ")
        androidx.compose.material3.OutlinedTextField(hText, { hText = it.filter(Char::isDigit).take(4) }, Modifier.width(110.dp),
            label = { androidx.compose.material3.Text("高さ px") }, singleLine = true, keyboardOptions = num)
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        androidx.compose.material3.OutlinedTextField(dText, { dText = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, Modifier.width(100.dp),
            label = { androidx.compose.material3.Text("倍率") }, singleLine = true, keyboardOptions = num)
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        val w = wText.toIntOrNull()
        val h = hText.toIntOrNull()
        val d = dText.toFloatOrNull()
        val ok = w != null && h != null && d != null && w in 100..8000 && h in 100..8000 && d in 0.5f..4f
        androidx.compose.material3.FilledTonalButton(enabled = ok, onClick = { WebUi.setScreen(com.obdmeter.web.ScreenSize(w!!, h!!, d!!)) }) {
            androidx.compose.material3.Text("適用")
        }
    }
    androidx.compose.material3.Text(
        "倍率は Android の画面密度 (1 dp が何 px か) です。大きくすると文字やボタンが大きくなります。" +
            "1920×720 の車載ナビは 1〜1.5 くらいが多いです。",
        color = sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
    )
}
