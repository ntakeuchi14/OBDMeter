package com.obdmeter.data

import com.obdmeter.AppPlatform
import com.obdmeter.util.Zip

/**
 * バックアップ zip の中身を作る・読む (Android と Web で共通。zip の形式は同じ)。
 */
object BackupZip {
    private const val MANIFEST = "manifest.json"
    private const val IMAGE_DIR = "images/"
    private const val FONT_DIR = "fonts/"
    private const val MAX_FONT = 40 * 1024 * 1024
    private const val MAX_MANIFEST = 2 * 1024 * 1024
    private const val MAX_IMAGE = 12 * 1024 * 1024
    private const val MAX_TOTAL = 240L * 1024 * 1024

    /** zip のデータと、入れた画像の数を返す */
    suspend fun build(platform: AppPlatform, manifest: BackupManifest): Pair<ByteArray, Int> {
        val entries = ArrayList<Zip.Entry>()
        entries += Zip.Entry(MANIFEST, BackupLogic.encode(manifest).encodeToByteArray())
        var images = 0
        for (name in BackupLogic.referencedImages(manifest.dashboards)) {
            if (!BackupLogic.SAFE_IMAGE_NAME.matches(name)) continue
            val bytes = platform.imageBytes(name) ?: continue
            entries += Zip.Entry(IMAGE_DIR + name, bytes)
            images++
        }
        for (font in manifest.fonts) {
            if (!BackupLogic.SAFE_FONT_NAME.matches(font.file)) continue
            val bytes = platform.fontBytes(font) ?: continue
            entries += Zip.Entry(FONT_DIR + font.file, bytes)
        }
        return Zip.write(entries) to images
    }

    fun read(zip: ByteArray): LoadedBackup {
        var total = 0L
        val entries = Zip.read(zip) { name ->
            when {
                name == MANIFEST -> MAX_MANIFEST
                // 想定外のパス (../ など) やファイル名は無視する
                name.startsWith(IMAGE_DIR) && BackupLogic.SAFE_IMAGE_NAME.matches(name.removePrefix(IMAGE_DIR)) -> MAX_IMAGE
                name.startsWith(FONT_DIR) && BackupLogic.SAFE_FONT_NAME.matches(name.removePrefix(FONT_DIR)) -> MAX_FONT
                else -> null
            }
        }
        var manifestText: String? = null
        val images = mutableMapOf<String, ByteArray>()
        val fonts = mutableMapOf<String, ByteArray>()
        for (e in entries) {
            if (e.name == MANIFEST) { manifestText = e.data.decodeToString(); continue }
            total += e.data.size
            if (total > MAX_TOTAL) throw IllegalArgumentException("ファイルが大きすぎます")
            if (e.name.startsWith(IMAGE_DIR)) images[e.name.removePrefix(IMAGE_DIR)] = e.data
            else fonts[e.name.removePrefix(FONT_DIR)] = e.data
        }
        val text = manifestText ?: throw IllegalArgumentException(
            "OBD Meter の設定ファイルではありません (manifest.json がありません)"
        )
        return LoadedBackup(BackupLogic.decode(text), images, fonts)
    }

    /**
     * フォントを端末のライブラリに保存する。同じ内容のフォントが既にあればそれを使う。
     * @return 「ファイル内のフォント id → 端末の id」と、ライブラリに新しく加わったフォント
     */
    suspend fun saveFonts(platform: AppPlatform, backup: LoadedBackup, library: List<FontEntry>): Pair<Map<String, String>, List<FontEntry>> {
        val rename = mutableMapOf<String, String>()
        val added = mutableListOf<FontEntry>()
        for (entry in backup.manifest.fonts) {
            val bytes = backup.fonts[entry.file] ?: continue
            val ext = entry.file.substringAfterLast('.')
            val saved = runCatching { platform.saveFont(bytes, "${entry.name}.$ext", library + added) }.getOrNull() ?: continue
            rename[entry.id] = saved.id
            if (library.none { it.id == saved.id } && added.none { it.id == saved.id }) {
                added += saved.copy(name = entry.name)
            }
        }
        return rename to added
    }

    /** 画像を保存し、「ファイル内の名前 → 保存した名前」を返す */
    suspend fun saveImages(platform: AppPlatform, backup: LoadedBackup): Map<String, String> {
        val used = BackupLogic.referencedImages(backup.manifest.dashboards)
        val result = mutableMapOf<String, String>()
        for ((name, bytes) in backup.images) {
            if (name !in used) continue
            platform.saveImageBytes(bytes)?.let { result[name] = it }
        }
        return result
    }
}
