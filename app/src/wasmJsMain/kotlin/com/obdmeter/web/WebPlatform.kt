package com.obdmeter.web

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.obdmeter.AppPlatform
import com.obdmeter.DeviceSensorSource
import com.obdmeter.core.CommLogger
import com.obdmeter.core.DeviceItems
import com.obdmeter.core.ObdProtocol
import com.obdmeter.core.SimulatorProtocol
import com.obdmeter.data.AppSettings
import com.obdmeter.data.ConnectionType
import com.obdmeter.data.CustomPid
import com.obdmeter.data.Dashboard
import com.obdmeter.data.Defaults
import com.obdmeter.data.FontEntry
import com.obdmeter.data.HistoryStore
import com.obdmeter.data.Layout
import com.obdmeter.data.MemoryHistoryStore
import com.obdmeter.data.Repository
import com.obdmeter.util.Zip
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt
import kotlin.random.Random

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Web 版で保存する設定 (アダプター関係は持たない) */
@Serializable
private data class WebSettings(
    val alwaysShowToolbar: Boolean = true,
    val lastPageId: Long? = null,
    val gridStep: Int = AppSettings().gridStep,
    val pollIntervalsMs: Map<String, Int> = AppSettings().pollIntervalsMs,
)

/** 設定とページをブラウザーの保存領域 (localStorage) に保存する */
class WebRepository : Repository {
    private fun <T> load(key: String, decode: (String) -> T): T? =
        LocalStore.get(key)?.let { runCatching { decode(it) }.getOrNull() }

    private val settingsState = MutableStateFlow(
        (load("obd.settings") { json.decodeFromString<WebSettings>(it) } ?: WebSettings()).toApp()
    )
    private val dashboardsState = MutableStateFlow(
        load("obd.dashboards") { json.decodeFromString<List<Dashboard>>(it) }
            ?.takeIf { it.isNotEmpty() }?.map(Layout::normalize) ?: Defaults.dashboards
    )
    private val customPidsState = MutableStateFlow(load("obd.customPids") { json.decodeFromString<List<CustomPid>>(it) }.orEmpty())
    private val fontsState = MutableStateFlow(load("obd.fonts") { json.decodeFromString<List<FontEntry>>(it) }.orEmpty())

    override val settings: Flow<AppSettings> = settingsState
    override val dashboards: Flow<List<Dashboard>> = dashboardsState
    override val customPids: Flow<List<CustomPid>> = customPidsState
    override val fonts: Flow<List<FontEntry>> = fontsState

    private fun WebSettings.toApp() = AppSettings(
        connectionType = ConnectionType.SIMULATOR,
        autoConnect = true,
        autoReconnect = true,
        alwaysShowToolbar = alwaysShowToolbar,
        lastPageId = lastPageId,
        gridStep = gridStep,
        pollIntervalsMs = pollIntervalsMs,
    )

    private fun save(key: String, value: String) {
        if (!LocalStore.set(key, value)) WebUi.toast("ブラウザーに保存できませんでした (保存領域がいっぱいです)")
    }

    override suspend fun saveFonts(list: List<FontEntry>) {
        fontsState.value = list
        save("obd.fonts", json.encodeToString(list))
    }

    override suspend fun saveCustomPids(list: List<CustomPid>) {
        customPidsState.value = list
        save("obd.customPids", json.encodeToString(list))
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val s = transform(settingsState.value).copy(connectionType = ConnectionType.SIMULATOR)
        settingsState.value = s
        save("obd.settings", json.encodeToString(WebSettings(s.alwaysShowToolbar, s.lastPageId, s.gridStep, s.pollIntervalsMs)))
    }

    override suspend fun saveDashboards(list: List<Dashboard>) {
        dashboardsState.value = list
        save("obd.dashboards", json.encodeToString(list))
    }
}

/**
 * 端末のセンサーの代わり (Web では、それらしく動く値を出す)
 */
class WebSensors(private val scope: CoroutineScope) : DeviceSensorSource {
    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    override val values: StateFlow<Map<String, Double>> = _values
    private var active: Set<String> = emptySet()
    private var ticker: Job? = null

    override fun setActive(keys: Set<String>) {
        active = keys
        if (keys.isEmpty()) {
            ticker?.cancel(); ticker = null
            _values.value = emptyMap()
            return
        }
        if (ticker == null) ticker = scope.launch {
            while (isActive) {
                val t = com.obdmeter.util.nowMs()
                _values.value = active.associateWith { k -> fake(k, t) }
                delay(50)
            }
        }
    }

    private fun fake(key: String, t: Long): Double {
        DeviceItems.testSignal(key, t)?.let { return it }
        val def = DeviceItems.ALL.firstOrNull { it.key == key } ?: return 0.0
        val phase = (t % 20_000L) / 20_000.0 * 2 * kotlin.math.PI + key.hashCode() % 7
        val mid = (def.min + def.max) / 2
        val amp = (def.max - def.min) / 3
        return mid + amp * kotlin.math.sin(phase) + amp * 0.05 * (Random.nextDouble() - 0.5)
    }
}

/** Web 版の [AppPlatform] (保存はブラウザー、値はシミュレーター) */
class WebPlatform : AppPlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val repo: Repository = WebRepository()
    override val history: HistoryStore = MemoryHistoryStore(maxRowsPerSeries = 50_000)
    override val deviceSensors: DeviceSensorSource = WebSensors(scope)
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
    // ブラウザーは 1 本のスレッドで動くので、読み出しの合間に画面の更新を挟む
    override val minCycleMs: Long = 30
    override val appVersion: String get() = appVersion().ifBlank { "web" }

    override suspend fun createProtocol(settings: AppSettings, log: CommLogger, status: (String) -> Unit): ObdProtocol =
        SimulatorProtocol(responseDelayMs = 0)

    override fun defaultCellAspect(): Float = (1280f / Layout.COLS) / (720f / Layout.ROWS)

    // ---- 画像 ----

    private val imageCache = HashMap<String, ImageBitmap>()

    suspend fun loadImage(name: String): ImageBitmap? {
        imageCache[name]?.let { return it }
        val bytes = FileStore.get("img/$name") ?: return null
        val img = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() ?: return null
        if (imageCache.size > 24) imageCache.clear()
        imageCache[name] = img
        return img
    }

    private fun newName(prefix: String): String =
        prefix + (1..12).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

    override suspend fun importImage(bytes: ByteArray, maxSize: Int): String {
        val img = runCatching { Image.makeFromEncoded(bytes) }.getOrNull()
            ?: throw IllegalArgumentException("画像を読み込めませんでした")
        val longest = maxOf(img.width, img.height)
        val png = if (longest > maxSize) {
            val scale = maxSize.toFloat() / longest
            val w = (img.width * scale).roundToInt().coerceAtLeast(1)
            val h = (img.height * scale).roundToInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(img, Rect.makeWH(w.toFloat(), h.toFloat()))
            surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
        } else {
            img.encodeToData(EncodedImageFormat.PNG)?.bytes
        } ?: throw IllegalArgumentException("画像を読み込めませんでした")
        val name = newName("bg_") + ".png"
        FileStore.put("img/$name", png)
        return name
    }

    override suspend fun saveImageBytes(bytes: ByteArray): String? {
        if (runCatching { Image.makeFromEncoded(bytes) }.isFailure) return null
        val name = newName("bg_") + ".png"
        FileStore.put("img/$name", bytes)
        return name
    }

    override suspend fun imageBytes(name: String): ByteArray? = FileStore.get("img/$name")

    override suspend fun cleanupImages(used: Set<String>) {
        for (k in FileStore.keys()) {
            if (k.startsWith("img/") && k.removePrefix("img/") !in used) {
                FileStore.delete(k)
                imageCache.remove(k.removePrefix("img/"))
            }
        }
    }

    // ---- フォント ----

    /** 読み込み済みのフォントのデータ (Compose で使うには同期で読める必要がある) */
    val fontData = HashMap<String, ByteArray>()

    suspend fun loadFont(entry: FontEntry): ByteArray? =
        fontData[entry.file] ?: FileStore.get("font/${entry.file}")?.also { fontData[entry.file] = it }

    override suspend fun saveFont(bytes: ByteArray, displayName: String, existing: List<FontEntry>): FontEntry {
        val sha = "crc" + Zip.crc32(bytes).toUInt().toString(16) + "_" + bytes.size
        existing.firstOrNull { it.sha == sha }?.let { return it }
        val ok = runCatching { FontMgr.default.makeFromData(Data.makeFromBytes(bytes)) != null }.getOrDefault(false)
        if (!ok) throw IllegalArgumentException("フォントとして読み込めませんでした (.ttf / .otf を選んでください)")
        val ext = displayName.substringAfterLast('.', "").lowercase().takeIf { it == "ttf" || it == "otf" } ?: "ttf"
        val id = newName("font_")
        val file = "$id.$ext"
        FileStore.put("font/$file", bytes)
        fontData[file] = bytes
        return FontEntry(id = id, name = displayName.substringBeforeLast('.').ifBlank { id }, file = file, sha = sha)
    }

    override suspend fun fontBytes(entry: FontEntry): ByteArray? = loadFont(entry)

    override fun deleteFont(entry: FontEntry) {
        fontData.remove(entry.file)
        scope.launch { runCatching { FileStore.delete("font/${entry.file}") } }
    }
}
