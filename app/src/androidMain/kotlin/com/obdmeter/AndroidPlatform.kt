package com.obdmeter

import android.content.Context
import com.obdmeter.core.BmwFastProtocol
import com.obdmeter.core.CommLogger
import com.obdmeter.core.Elm327Protocol
import com.obdmeter.core.ObdProtocol
import com.obdmeter.core.SimulatorProtocol
import com.obdmeter.data.AppSettings
import com.obdmeter.data.ConnectionType
import com.obdmeter.data.DataStoreRepository
import com.obdmeter.data.FontEntry
import com.obdmeter.data.GaugeFonts
import com.obdmeter.data.GaugeImages
import com.obdmeter.data.HistoryStore
import com.obdmeter.data.Repository
import com.obdmeter.data.SqliteHistoryStore
import com.obdmeter.data.UsbAdapter
import com.obdmeter.sensors.DeviceSensors
import com.obdmeter.usb.UsbSerialTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Android 版の [AppPlatform] (DataStore・SQLite・USB アダプター・端末のセンサー・アプリ内のファイル) */
class AndroidPlatform(context: Context) : AppPlatform {
    val context: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val repo: Repository = DataStoreRepository(this.context)
    override val history: HistoryStore = SqliteHistoryStore(this.context, scope)
    val sensors = DeviceSensors(this.context, scope)
    override val deviceSensors get() = sensors
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    override val minCycleMs: Long = 0

    override val appVersion: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

    override fun defaultCellAspect(): Float {
        val m = context.resources.displayMetrics
        val long = maxOf(m.widthPixels, m.heightPixels).toFloat()
        val short = minOf(m.widthPixels, m.heightPixels).toFloat()
        // 横向きで使う前提: 24 列 × 12 行
        return (long / com.obdmeter.data.Layout.COLS) / (short / com.obdmeter.data.Layout.ROWS)
    }

    override suspend fun createProtocol(settings: AppSettings, log: CommLogger, status: (String) -> Unit): ObdProtocol {
        if (settings.connectionType == ConnectionType.SIMULATOR) return SimulatorProtocol()

        val drivers = UsbSerialTransport.findDrivers(context)
        val driver = drivers.firstOrNull()
            ?: throw IllegalStateException("USB アダプターが見つかりません。OTG ケーブルとアダプターの接続を確認してください")
        val name = UsbSerialTransport.describe(driver.device)
        log.log(CommLogger.Direction.INFO, "USB 機器: $name (${driver.javaClass.simpleName})")

        status("USB の使用許可を待っています…")
        if (!UsbSerialTransport.requestPermission(context, driver.device)) {
            throw IllegalStateException("USB の使用が許可されませんでした")
        }

        return when (settings.usbAdapter) {
            UsbAdapter.OBDLINK -> {
                // アダプターは電源投入時 115200。設定の速度へは接続後に切り替える
                val t = UsbSerialTransport(context, driver, 115200, dtr = true, rts = true)
                t.open()
                Elm327Protocol(
                    t,
                    Elm327Protocol.Options(
                        protocol = settings.elmProtocol,
                        singleResponseHint = settings.elmSingleResponseHint,
                        baudRate = settings.elmBaudRate,
                    ),
                    log,
                )
            }
            UsbAdapter.DCAN -> {
                // K+DCAN は 115200bps 固定。EdiabasLib と同じく DTR=ON (エコーありアダプター)
                val t = UsbSerialTransport(context, driver, 115200, dtr = true, rts = false)
                t.open()
                BmwFastProtocol(
                    t,
                    BmwFastProtocol.Options(
                        targetAddress = settings.dcanTargetAddress,
                        adapterEcho = settings.dcanEcho,
                    ),
                    log,
                )
            }
        }
    }

    override suspend fun cleanupImages(used: Set<String>) { GaugeImages.cleanup(context, used) }
    override suspend fun importImage(bytes: ByteArray, maxSize: Int): String = GaugeImages.importBytes(context, bytes, maxSize)
    override suspend fun saveImageBytes(bytes: ByteArray): String? = GaugeImages.saveBytes(context, bytes)
    override suspend fun saveFont(bytes: ByteArray, displayName: String, existing: List<FontEntry>): FontEntry =
        GaugeFonts.save(context, bytes, displayName, existing)
    override suspend fun fontBytes(entry: FontEntry): ByteArray? = withContext(Dispatchers.IO) {
        GaugeFonts.file(context, entry).takeIf { it.exists() }?.readBytes()
    }
    override suspend fun imageBytes(name: String): ByteArray? = withContext(Dispatchers.IO) {
        GaugeImages.file(context, name).takeIf { it.exists() }?.readBytes()
    }
    override fun deleteFont(entry: FontEntry) = GaugeFonts.delete(context, entry)

    override suspend fun connectionTest(settings: AppSettings, log: CommLogger, out: (String) -> Unit) {
        com.obdmeter.session.ConnectionTest(context, log).run(settings, out)
    }

    override suspend fun bmwSearch(settings: AppSettings, log: CommLogger, targets: List<Long>, ranges: List<Pair<Int, IntRange>>, out: (String) -> Unit) {
        com.obdmeter.session.ConnectionTest(context, log).searchBmw(settings, targets, ranges, out)
    }
}

/** プロセスに 1 つの [AppCore] (アプリの画面とフロート表示で共有) */
object AppCoreHolder {
    @Volatile private var instance: AppCore? = null
    fun get(context: Context): AppCore =
        instance ?: synchronized(this) { instance ?: AppCore(AndroidPlatform(context)).also { instance = it } }
}

fun AppCore.Companion.get(context: Context): AppCore = AppCoreHolder.get(context)

val AppCore.android: AndroidPlatform get() = platform as AndroidPlatform
val AppCore.app: Context get() = android.context
