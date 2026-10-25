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
    FontFamily(androidx.compose.ui.text.platform.Font(entry.file, androidLikeMetrics(bytes)))
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

@Composable
actual fun deviceScreenInfo(): String? = null

/**
 * Android と同じ文字の高さ・位置にするため、フォントの OS/2 の USE_TYPO_METRICS を外す。
 * (ブラウザー版の描画はこの印があると OS/2 の値で行の高さを決めるが、Android は hhea の値を使うため、
 *  この印があって値が違うフォントでは、数値の上下の位置が Android とずれていた)
 */
private fun androidLikeMetrics(bytes: ByteArray): ByteArray {
    fun u16(i: Int) = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
    fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
    return runCatching {
        val numTables = u16(4)
        for (t in 0 until numTables) {
            val rec = 12 + t * 16
            if (bytes.decodeToString(rec, rec + 4) == "OS/2") {
                val sel = u32(rec + 8) + 62
                val v = u16(sel)
                if (v and 0x80 == 0) return bytes
                val out = bytes.copyOf()
                val nv = v and 0x80.inv()
                out[sel] = (nv shr 8).toByte()
                out[sel + 1] = nv.toByte()
                return out
            }
        }
        bytes
    }.getOrDefault(bytes)
}
