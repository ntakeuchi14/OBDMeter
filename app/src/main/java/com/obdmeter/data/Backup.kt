package com.obdmeter.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 設定のバックアップ (エクスポート / インポート) の中身。
 *
 * ファイルは zip で、
 *   manifest.json   … この [BackupManifest] (ダッシュボード・メーター設定・カスタム PID)
 *   images/xxx.png  … メーターの背景画像
 * を含む。別の端末や再インストール後でも、画像込みでそのまま復元できる。
 */
@Serializable
data class BackupManifest(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val appVersion: String = "",
    val exportedAt: String = "",
    val dashboards: List<Dashboard> = emptyList(),
    val customPids: List<CustomPid> = emptyList(),
    /** 取り込んだフォントの一覧 (ファイルは zip の fonts/ に入る) */
    val fonts: List<FontEntry> = emptyList(),
) {
    companion object {
        const val FORMAT = "obdmeter-backup"
        const val VERSION = 1
    }
}

/** インポートの方法 */
enum class ImportMode {
    /** 今のページとカスタム PID をすべて消して、ファイルの内容に置き換える */
    REPLACE,
    /** 今の内容を残したまま、ファイルのページとカスタム PID を追加する */
    MERGE,
}

/** 読み込んだバックアップ (適用前)。画像はバイト列のまま持つ */
class LoadedBackup(
    val manifest: BackupManifest,
    val images: Map<String, ByteArray>,
    /** フォントファイル (ファイル名 → 中身) */
    val fonts: Map<String, ByteArray> = emptyMap(),
) {
    val fontCount get() = manifest.fonts.count { it.file in fonts }
    val pageCount get() = manifest.dashboards.size
    val gaugeCount get() = manifest.dashboards.sumOf { it.gauges.size }
    val customPidCount get() = manifest.customPids.size
    val imageCount get() = images.size
}

/** 適用結果: 保存すべきダッシュボード・カスタム PID と、画像名の付け替え表 */
data class MergeResult(
    val dashboards: List<Dashboard>,
    val customPids: List<CustomPid>,
)

object BackupLogic {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** 背景画像のファイル名として受け付ける形 (zip の中のパス攻撃を防ぐ) */
    val SAFE_IMAGE_NAME = Regex("^[A-Za-z0-9_.-]{1,80}\\.png$")

    /** フォントのファイル名として受け付ける形 */
    val SAFE_FONT_NAME = Regex("^[A-Za-z0-9_-]{1,60}\\.(ttf|otf)$")

    fun encode(manifest: BackupManifest): String = json.encodeToString(manifest)

    fun decode(text: String): BackupManifest {
        // "format" が書かれていないファイル ({} など) を既定値で通してしまわないよう、生の JSON で確認する
        val format = runCatching {
            (json.parseToJsonElement(text) as? JsonObject)?.get("format")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (format != BackupManifest.FORMAT) throw IllegalArgumentException("OBD Meter の設定ファイルではありません")
        val m = runCatching { json.decodeFromString<BackupManifest>(text) }
            .getOrElse { throw IllegalArgumentException("設定ファイルの形式が正しくありません") }
        if (m.version > BackupManifest.VERSION) {
            throw IllegalArgumentException("新しいバージョンのアプリで作られたファイルです。アプリを更新してください")
        }
        return m
    }

    /** ダッシュボードが参照している背景画像の名前 */
    fun referencedImages(dashboards: List<Dashboard>): Set<String> =
        dashboards.flatMap { it.gauges }.mapNotNull { it.design.backgroundImage }.toSet()

    /**
     * 読み込んだ内容を今の内容に適用した結果を作る。
     * @param imageRename 取り込んだ画像の「ファイル内の名前 → 端末に保存した名前」
     * @param newId 新しい id を発行する関数 (テストでは連番)
     */
    fun apply(
        current: List<Dashboard>,
        currentPids: List<CustomPid>,
        incoming: BackupManifest,
        imageRename: Map<String, String>,
        mode: ImportMode,
        newId: () -> Long,
        fontRename: Map<String, String> = emptyMap(),
    ): MergeResult {
        // 画像・フォントの参照を付け替える (取り込めなかったものは外して既定に戻す)
        fun fixImages(d: Dashboard) = d.copy(gauges = d.gauges.map { g ->
            g.copy(design = g.design.copy(
                backgroundImage = g.design.backgroundImage?.let { imageRename[it] },
                valueFont = g.design.valueFont?.let { fontRename[it] },
                labelFont = g.design.labelFont?.let { fontRename[it] },
            ))
        })

        if (mode == ImportMode.REPLACE) {
            val pages = incoming.dashboards.map { Layout.normalize(fixImages(it)) }
            return MergeResult(
                dashboards = pages.ifEmpty { Defaults.dashboards },
                customPids = incoming.customPids,
            )
        }

        // ---- 追加 ----
        // カスタム PID: 中身が同じものが既にあればそれを使い、無ければ新しい id で追加する
        val pids = currentPids.toMutableList()
        val keyRename = mutableMapOf<String, String>()
        for (p in incoming.customPids) {
            val same = pids.firstOrNull { it.copy(id = 0) == p.copy(id = 0) }
            val target = same ?: p.copy(id = newId()).also { pids += it }
            keyRename[p.key] = target.key
        }
        // ページ: 新しい id を振り、カスタム PID の参照を付け替えて末尾に追加
        val existingNames = current.map { it.name }.toMutableSet()
        val added = incoming.dashboards.map { d ->
            var name = d.name
            if (name in existingNames) {
                var n = 2
                while ("$name ($n)" in existingNames) n++
                name = "$name ($n)"
            }
            existingNames += name
            val fixed = fixImages(d).let { x ->
                x.copy(gauges = x.gauges.map { g ->
                    g.copy(
                        pidKey = keyRename[g.pidKey] ?: g.pidKey,
                        fallbackKey = g.fallbackKey?.let { keyRename[it] ?: it },
                    )
                })
            }
            Layout.normalize(fixed.copy(id = newId(), name = name))
        }
        return MergeResult(dashboards = current + added, customPids = pids)
    }
}
