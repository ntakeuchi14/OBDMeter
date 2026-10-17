package com.obdmeter.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DASHBOARDS = stringPreferencesKey("dashboards_json")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    val dashboards: Flow<List<Dashboard>> = context.dataStore.data.map { p ->
        p[Keys.DASHBOARDS]
            ?.let { runCatching { json.decodeFromString<List<Dashboard>>(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: Defaults.dashboards
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
            p[Keys.KEEP_SCREEN_ON] = s.keepScreenOn
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
            keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: d.keepScreenOn,
        )
    }
}
