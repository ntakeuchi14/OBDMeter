package com.obdmeter.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "obd_meter")

/** 設定とダッシュボード構成を端末内 (DataStore) に保存する。 */
class Repository(private val context: Context) {

    private object Keys {
        val CONNECTION = stringPreferencesKey("connection_type")
        val USB_ADAPTER = stringPreferencesKey("usb_adapter")
        val ELM_BAUD = intPreferencesKey("elm_baud")
        val ELM_PROTOCOL = stringPreferencesKey("elm_protocol")
        val ELM_HINT = booleanPreferencesKey("elm_hint")
        val DCAN_TARGET = intPreferencesKey("dcan_target")
        val DCAN_ECHO = booleanPreferencesKey("dcan_echo")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val ALWAYS_TOOLBAR = booleanPreferencesKey("always_show_toolbar")
        val WAIT_FOR_VEHICLE = booleanPreferencesKey("wait_for_vehicle")
        val WAIT_TIMEOUT = intPreferencesKey("wait_timeout_min")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val FLOAT_PAGE = longPreferencesKey("float_page")
        val FLOAT_PLACEMENTS = stringPreferencesKey("float_placements_json")
        val POLL_INTERVALS = stringPreferencesKey("poll_intervals_json")
        val FLOAT_OPACITY = floatPreferencesKey("float_opacity")
        val FLOAT_BG = floatPreferencesKey("float_background_v2")
        val FLOAT_ON_LEAVE = booleanPreferencesKey("float_on_leave")
        val START_IN_FLOAT = booleanPreferencesKey("start_in_float")
        val LAST_PAGE = longPreferencesKey("last_page")
        val KEY_PAGE_NAV = booleanPreferencesKey("key_page_nav")
        val DASHBOARDS = stringPreferencesKey("dashboards_json")
        val CUSTOM_PIDS = stringPreferencesKey("custom_pids_json")
        val FONTS = stringPreferencesKey("fonts_json")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    val dashboards: Flow<List<Dashboard>> = context.dataStore.data.map { p ->
        p[Keys.DASHBOARDS]
            ?.let { runCatching { json.decodeFromString<List<Dashboard>>(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?.map(Layout::normalize) // 旧形式 (列数のみ) の保存データを自由配置に移行
            ?: Defaults.dashboards
    }

    val customPids: Flow<List<CustomPid>> = context.dataStore.data.map { p ->
        p[Keys.CUSTOM_PIDS]
            ?.let { runCatching { json.decodeFromString<List<CustomPid>>(it) }.getOrNull() }
            .orEmpty()
    }

    /** 取り込んだフォントの一覧 */
    val fonts: Flow<List<FontEntry>> = context.dataStore.data.map { p ->
        p[Keys.FONTS]
            ?.let { runCatching { json.decodeFromString<List<FontEntry>>(it) }.getOrNull() }
            .orEmpty()
    }

    suspend fun saveFonts(list: List<FontEntry>) {
        context.dataStore.edit { it[Keys.FONTS] = json.encodeToString(list) }
    }

    suspend fun saveCustomPids(list: List<CustomPid>) {
        context.dataStore.edit { it[Keys.CUSTOM_PIDS] = json.encodeToString(list) }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[Keys.CONNECTION] = s.connectionType.name
            p[Keys.USB_ADAPTER] = s.usbAdapter.name
            p[Keys.ELM_BAUD] = s.elmBaudRate
            p[Keys.ELM_PROTOCOL] = s.elmProtocol
            p[Keys.ELM_HINT] = s.elmSingleResponseHint
            p[Keys.DCAN_TARGET] = s.dcanTargetAddress
            p[Keys.DCAN_ECHO] = s.dcanEcho
            p[Keys.AUTO_RECONNECT] = s.autoReconnect
            p[Keys.AUTO_CONNECT] = s.autoConnect
            p[Keys.ALWAYS_TOOLBAR] = s.alwaysShowToolbar
            p[Keys.WAIT_FOR_VEHICLE] = s.waitForVehicle
            p[Keys.WAIT_TIMEOUT] = s.waitTimeoutMin
            p[Keys.KEEP_SCREEN_ON] = s.keepScreenOn
            s.floatPageId?.let { p[Keys.FLOAT_PAGE] = it } ?: p.remove(Keys.FLOAT_PAGE)
            p[Keys.FLOAT_PLACEMENTS] = json.encodeToString(s.floatPlacements)
            p[Keys.FLOAT_OPACITY] = s.floatOpacity
            p[Keys.FLOAT_BG] = s.floatBackground
            p[Keys.FLOAT_ON_LEAVE] = s.floatOnLeave
            p[Keys.START_IN_FLOAT] = s.startInFloat
            s.lastPageId?.let { p[Keys.LAST_PAGE] = it } ?: p.remove(Keys.LAST_PAGE)
            p[Keys.KEY_PAGE_NAV] = s.keyPageNav
            p[Keys.POLL_INTERVALS] = json.encodeToString(s.pollIntervalsMs)
        }
    }

    suspend fun saveDashboards(list: List<Dashboard>) {
        context.dataStore.edit { it[Keys.DASHBOARDS] = json.encodeToString(list) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            connectionType = this[Keys.CONNECTION]
                ?.let { runCatching { ConnectionType.valueOf(it) }.getOrNull() } ?: d.connectionType,
            usbAdapter = this[Keys.USB_ADAPTER]
                ?.let { runCatching { UsbAdapter.valueOf(it) }.getOrNull() } ?: d.usbAdapter,
            elmBaudRate = this[Keys.ELM_BAUD] ?: d.elmBaudRate,
            elmProtocol = this[Keys.ELM_PROTOCOL] ?: d.elmProtocol,
            elmSingleResponseHint = this[Keys.ELM_HINT] ?: d.elmSingleResponseHint,
            dcanTargetAddress = this[Keys.DCAN_TARGET] ?: d.dcanTargetAddress,
            dcanEcho = this[Keys.DCAN_ECHO] ?: d.dcanEcho,
            autoReconnect = this[Keys.AUTO_RECONNECT] ?: d.autoReconnect,
            autoConnect = this[Keys.AUTO_CONNECT] ?: d.autoConnect,
            alwaysShowToolbar = this[Keys.ALWAYS_TOOLBAR] ?: d.alwaysShowToolbar,
            waitForVehicle = this[Keys.WAIT_FOR_VEHICLE] ?: d.waitForVehicle,
            waitTimeoutMin = this[Keys.WAIT_TIMEOUT] ?: d.waitTimeoutMin,
            keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: d.keepScreenOn,
            floatPageId = this[Keys.FLOAT_PAGE],
            floatPlacements = this[Keys.FLOAT_PLACEMENTS]
                ?.let { runCatching { json.decodeFromString<Map<Long, FloatPlacement>>(it) }.getOrNull() }
                ?: d.floatPlacements,
            floatOpacity = this[Keys.FLOAT_OPACITY] ?: d.floatOpacity,
            floatBackground = this[Keys.FLOAT_BG] ?: d.floatBackground,
            floatOnLeave = this[Keys.FLOAT_ON_LEAVE] ?: d.floatOnLeave,
            startInFloat = this[Keys.START_IN_FLOAT] ?: d.startInFloat,
            lastPageId = this[Keys.LAST_PAGE],
            keyPageNav = this[Keys.KEY_PAGE_NAV] ?: d.keyPageNav,
            pollIntervalsMs = this[Keys.POLL_INTERVALS]
                ?.let { runCatching { json.decodeFromString<Map<String, Int>>(it) }.getOrNull() }
                ?: d.pollIntervalsMs,
        )
    }
}
