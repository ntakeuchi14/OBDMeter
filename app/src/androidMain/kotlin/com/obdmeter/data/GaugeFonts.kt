package com.obdmeter.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * フォントファイル (.ttf / .otf) の保存と読み込み。
 * 一度取り込んだフォントはライブラリに残り、別のメーターでもそのまま選べる。
 */
object GaugeFonts {
    private const val MAX_SIZE = 40L * 1024 * 1024
    private val families = mutableMapOf<String, FontFamily>()

    private fun dir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    fun file(context: Context, entry: FontEntry): File = File(dir(context), entry.file)

    /** ライブラリのフォントを Compose の FontFamily にする (キャッシュ付き)。読めなければ null */
    @OptIn(ExperimentalTextApi::class)
    fun family(context: Context, entry: FontEntry): FontFamily? = synchronized(families) {
        families[entry.id]?.let { return it }
        val f = file(context, entry)
        if (!f.exists()) return null
        runCatching { FontFamily(Font(f)) }.getOrNull()?.also { families[entry.id] = it }
    }

    /**
     * 選んだファイルを取り込む。同じ内容のフォントが [existing] にあればそれを返す (新しく保存しない)。
     * フォントとして読めないファイルなら例外。
     */
    suspend fun import(context: Context, uri: Uri, existing: List<FontEntry>): FontEntry = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = "フォント"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { displayName = it }
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_SIZE) throw IllegalArgumentException("フォントファイルが大きすぎます (40MB まで)")
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("ファイルを開けませんでした")
        save(context, bytes, displayName, existing)
    }

    /** バイト列からフォントを保存する (バックアップの読み込みでも使う) */
    suspend fun save(context: Context, bytes: ByteArray, displayName: String, existing: List<FontEntry>): FontEntry =
        withContext(Dispatchers.IO) {
            val sha = sha1(bytes)
            existing.firstOrNull { it.sha == sha && File(dir(context), it.file).exists() }?.let { return@withContext it }

            val ext = displayName.substringAfterLast('.', "").lowercase().takeIf { it == "ttf" || it == "otf" } ?: "ttf"
            val id = "font_" + UUID.randomUUID().toString().take(12)
            val f = File(dir(context), "$id.$ext")
            f.writeBytes(bytes)
            // 本当にフォントとして読めるか確認 (読めなければ消してエラー)
            val ok = runCatching { Typeface.Builder(f).build() }.getOrNull() != null
            if (!ok) {
                f.delete()
                throw IllegalArgumentException("フォントとして読み込めませんでした (.ttf / .otf を選んでください)")
            }
            FontEntry(id = id, name = displayName.substringBeforeLast('.').ifBlank { id }, file = f.name, sha = sha)
        }

    fun delete(context: Context, entry: FontEntry) {
        synchronized(families) { families.remove(entry.id) }
        File(dir(context), entry.file).delete()
    }

    fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
