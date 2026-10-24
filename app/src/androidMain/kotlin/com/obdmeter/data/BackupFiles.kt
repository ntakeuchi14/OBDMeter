package com.obdmeter.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** バックアップ zip を Android のファイル選択で選んだ場所に書く・から読む (中身は [BackupZip]) */
object BackupFiles {
    private const val MAX_FILE = 250 * 1024 * 1024

    suspend fun write(context: Context, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("保存先を開けませんでした")
        out.use { it.write(bytes) }
    }

    suspend fun readBytes(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("ファイルを開けませんでした")
        input.use { s ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_FILE) throw IllegalArgumentException("ファイルが大きすぎます")
            }
            buf.toByteArray()
        }
    }
}
