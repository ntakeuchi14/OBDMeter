package com.obdmeter.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * メーター背景画像の保存と読み込み。
 * 選んだ画像はアプリ内にコピーして持つ (元の写真を消しても表示が消えないように)。
 * 透過 PNG はそのまま透過を保つ。大きすぎる画像は 1024px に縮小する。
 */
object GaugeImages {
    private const val MAX_SIZE = 1024
    private val cache = LruCache<String, ImageBitmap>(12)

    private fun dir(context: Context) = File(context.filesDir, "gauge_images").apply { mkdirs() }

    /** 画像を取り込み、保存したファイル名を返す。読めない画像なら例外。 */
    suspend fun import(context: Context, uri: Uri, maxSize: Int = MAX_SIZE): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("画像を読み込めませんでした")

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("画像を読み込めませんでした")
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > maxSize) {
            val scale = maxSize.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
        val name = "bg_" + UUID.randomUUID().toString().take(12) + ".png"
        File(dir(context), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name
    }

    /** 保存済み画像のファイル (エクスポート用) */
    fun file(context: Context, name: String): File = File(dir(context), name)

    /**
     * バックアップから取り出した画像を保存し、新しいファイル名を返す。
     * 画像として読めないデータなら null。
     */
    suspend fun saveBytes(context: Context, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val name = "bg_" + UUID.randomUUID().toString().take(12) + ".png"
        File(dir(context), name).writeBytes(bytes)
        name
    }

    /** 保存済みの画像を読む (キャッシュ付き)。無ければ null。 */
    suspend fun load(context: Context, name: String): ImageBitmap? {
        cache.get(name)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = File(dir(context), name)
            if (!f.exists()) return@withContext null
            BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { cache.put(name, it) }
        }
    }

    /** どのメーターからも使われていない画像を削除する */
    suspend fun cleanup(context: Context, used: Set<String>) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { f ->
            if (f.name !in used) {
                f.delete()
                cache.remove(f.name)
            }
        }
    }
}
