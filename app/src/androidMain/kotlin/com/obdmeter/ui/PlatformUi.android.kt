package com.obdmeter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.obdmeter.data.BackupFiles
import com.obdmeter.data.FontEntry
import com.obdmeter.data.GaugeFonts
import com.obdmeter.data.GaugeImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual val isWebApp: Boolean = false

@Composable
actual fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable
actual fun rememberFileOpener(mimeTypes: List<String>, onPicked: (PickedFile) -> Unit): () -> Boolean {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onPicked)
    val deliver: (Uri?) -> Unit = { uri ->
        if (uri != null) callback(PickedFile(displayName(context, uri)) { BackupFiles.readBytes(context, uri) })
    }
    return if (mimeTypes.isNotEmpty() && mimeTypes.all { it.startsWith("image/") }) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), deliver)
        val open: () -> Boolean = { runCatching { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.isSuccess }
        open
    } else {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), deliver)
        val open: () -> Boolean = { runCatching { launcher.launch(mimeTypes.toTypedArray()) }.isSuccess }
        open
    }
}

@Composable
actual fun rememberFileSaver(mimeType: String, onTarget: (FileTarget) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onTarget)
    val saveFolder = LocalSaveFolder.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        if (uri != null) callback(FileTarget { bytes -> BackupFiles.write(context, uri, bytes); null })
    }
    return save@{ name ->
        val tree = saveFolder.folder.takeIf { it.isNotEmpty() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (tree != null && hasTreeAccess(context, tree)) {
            // 既定の保存先: 聞かずにそのフォルダーへ保存する
            callback(FileTarget { bytes -> writeToTree(context, tree, saveFolder.name, name, mimeType, bytes) })
            return@save
        }
        if (tree != null) Toast.makeText(context, "既定の保存先に書き込めなくなっています。保存先を選んでください (設定で選び直せます)", Toast.LENGTH_LONG).show()
        // 保存先を選ぶ画面が無い端末 (車載ナビなど) では、ダウンロードフォルダに直接保存する
        if (runCatching { launcher.launch(name) }.isFailure) {
            callback(FileTarget { bytes -> writeToDownloads(context, name, mimeType, bytes) })
        }
    }
}

@Composable
actual fun rememberShareText(): (subject: String, text: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { subject: String, text: String ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            runCatching { context.startActivity(Intent.createChooser(send, "共有")) }
        }
    }
}

@Composable
actual fun rememberGaugeImage(name: String?): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, name) {
        value = name?.let { GaugeImages.load(context, it) }
    }.value
}

@Composable
actual fun rememberFontFamily(entry: FontEntry?): FontFamily? {
    val context = LocalContext.current
    return remember(entry?.id, entry?.file) { entry?.let { GaugeFonts.family(context, it) } }
}

actual fun DrawScope.drawSoftShadow(path: Path, dx: Float, dy: Float, blur: Float, alpha: Float) {
    if (alpha <= 0f) return
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)
            if (blur > 0.5f) maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val ap = path.asAndroidPath()
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.translate(dx, dy)
        canvas.nativeCanvas.drawPath(ap, paint)
        canvas.nativeCanvas.restore()
    }
}

private fun displayName(context: Context, uri: Uri): String {
    var name = "file"
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
    }
    return name
}

/** ダウンロードフォルダ (Download/OBDMeter) に直接書き出す。保存先の説明を返す */
private suspend fun writeToDownloads(context: Context, name: String, mimeType: String, bytes: ByteArray): String =
    withContext(Dispatchers.IO) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/OBDMeter")
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("保存先を作れませんでした")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("ファイルを開けませんでした")
            "Download/OBDMeter/$name"
        } else {
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val f = java.io.File(dir, name)
            f.writeBytes(bytes)
            f.absolutePath
        }
    }

@Composable
actual fun rememberFloatPermission(): FloatPermissionState {
    val context = LocalContext.current
    var canDraw by remember { androidx.compose.runtime.mutableStateOf(com.obdmeter.overlay.FloatController.canDraw(context)) }
    // 許可画面から戻ってきたら確認し直す
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        canDraw = com.obdmeter.overlay.FloatController.canDraw(context)
    }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val open = { runCatching { context.startActivity(com.obdmeter.overlay.FloatController.permissionIntent(context)) }; Unit }
    return FloatPermissionState(
        canDraw = canDraw,
        request = {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notifyLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            if (!com.obdmeter.overlay.FloatController.canDraw(context)) open()
        },
        openSettings = open,
    )
}

@Composable
actual fun deviceScreenInfo(): String? {
    val context = LocalContext.current
    val m = context.resources.displayMetrics
    // 横向きで使うので長辺を横にする
    val w = maxOf(m.widthPixels, m.heightPixels)
    val h = minOf(m.widthPixels, m.heightPixels)
    val d = (kotlin.math.round(m.density * 1000) / 1000.0).toString().trimEnd('0').trimEnd('.')
    return "この端末の画面: $w × $h px、倍率 $d (${m.densityDpi} dpi)"
}


/** 既定の保存先のフォルダーに、まだ書き込めるか (アクセス許可が残っているか) */
private fun hasTreeAccess(context: Context, tree: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

/** フォルダー [tree] に [name] で保存する (同じ名前があれば、保存先のアプリが別の名前を付ける) */
private suspend fun writeToTree(context: Context, tree: Uri, folderName: String, name: String, mimeType: String, bytes: ByteArray): String =
    withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        val doc = android.provider.DocumentsContract.createDocument(cr, parent, mimeType, name) ?: error("保存先にファイルを作れませんでした")
        cr.openOutputStream(doc, "w")?.use { it.write(bytes) } ?: error("ファイルを開けませんでした")
        val saved = runCatching {
            cr.query(doc, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: name
        "${folderName.ifEmpty { "保存先" }}/$saved"
    }

@Composable
actual fun rememberFolderPicker(onPicked: (folder: String, name: String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!ok) {
            Toast.makeText(context, "このフォルダーには保存できません (別のフォルダーを選んでください)", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        callback(uri.toString(), folderLabel(context, uri))
    }
    return {
        if (runCatching { launcher.launch(null) }.isFailure) {
            Toast.makeText(context, "この端末にはフォルダーを選ぶ画面がありません", Toast.LENGTH_LONG).show()
        }
    }
}

/** フォルダーの表示名 (「アプリ名: フォルダー名」) */
private fun folderLabel(context: Context, tree: Uri): String {
    val cr = context.contentResolver
    val doc = runCatching {
        android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull()
    val dir = doc?.let {
        runCatching {
            cr.query(it, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    } ?: tree.lastPathSegment.orEmpty()
    val app = runCatching {
        val pm = context.packageManager
        tree.authority?.let { pm.resolveContentProvider(it, 0)?.loadLabel(pm)?.toString() }
    }.getOrNull()
    return if (app.isNullOrEmpty()) dir else "$app: $dir"
}

actual fun releaseSaveFolder(platform: com.obdmeter.AppPlatform?, folder: String) {
    val context = (platform as? com.obdmeter.AndroidPlatform)?.context ?: return
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}
