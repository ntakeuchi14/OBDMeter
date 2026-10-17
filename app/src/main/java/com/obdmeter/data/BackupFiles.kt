package com.obdmeter.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * バックアップ zip の書き出しと読み込み (Android のファイル選択で選んだ場所に対して)。
 */
object BackupFiles {
    private const val MANIFEST = "manifest.json"
    private const val IMAGE_DIR = "images/"
    private const val MAX_MANIFEST = 2 * 1024 * 1024
    private const val MAX_IMAGE = 12 * 1024 * 1024
    private const val MAX_TOTAL = 80 * 1024 * 1024

    /** 書き出した画像の数を返す */
    suspend fun export(context: Context, uri: Uri, manifest: BackupManifest): Int = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("保存先を開けませんでした")
        var images = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(BackupLogic.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (name in BackupLogic.referencedImages(manifest.dashboards)) {
                val f = GaugeImages.file(context, name)
                if (!f.exists() || !BackupLogic.SAFE_IMAGE_NAME.matches(name)) continue
                zip.putNextEntry(ZipEntry(IMAGE_DIR + name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                images++
            }
        }
        images
    }

    suspend fun read(context: Context, uri: Uri): LoadedBackup = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("ファイルを開けませんでした")
        var manifestText: String? = null
        val images = mutableMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                when {
                    e.name == MANIFEST -> manifestText = String(readLimited(zip, MAX_MANIFEST), Charsets.UTF_8)
                    e.name.startsWith(IMAGE_DIR) -> {
                        val name = e.name.removePrefix(IMAGE_DIR)
                        // 想定外のパス (../ など) やファイル名は無視する
                        if (BackupLogic.SAFE_IMAGE_NAME.matches(name)) {
                            val bytes = readLimited(zip, MAX_IMAGE)
                            total += bytes.size
                            if (total > MAX_TOTAL) throw IllegalArgumentException("ファイルが大きすぎます")
                            images[name] = bytes
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val text = manifestText ?: throw IllegalArgumentException(
            "OBD Meter の設定ファイルではありません (manifest.json がありません)"
        )
        LoadedBackup(BackupLogic.decode(text), images)
    }

    /** 画像を端末に保存し、「ファイル内の名前 → 保存した名前」を返す */
    suspend fun saveImages(context: Context, backup: LoadedBackup): Map<String, String> {
        val used = BackupLogic.referencedImages(backup.manifest.dashboards)
        val result = mutableMapOf<String, String>()
        for ((name, bytes) in backup.images) {
            if (name !in used) continue
            GaugeImages.saveBytes(context, bytes)?.let { result[name] = it }
        }
        return result
    }

    private fun readLimited(zip: ZipInputStream, limit: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = zip.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > limit) throw IllegalArgumentException("ファイル内のデータが大きすぎます")
        }
        return buf.toByteArray()
    }
}
