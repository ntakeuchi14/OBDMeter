package com.obdmeter.data

import kotlinx.coroutines.flow.Flow

/** 設定とダッシュボード構成の保存先 (Android は DataStore、Web はブラウザーの保存領域) */
interface Repository {
    val settings: Flow<AppSettings>
    val dashboards: Flow<List<Dashboard>>
    val customPids: Flow<List<CustomPid>>
    /** 取り込んだフォントの一覧 */
    val fonts: Flow<List<FontEntry>>

    suspend fun saveFonts(list: List<FontEntry>)
    suspend fun saveCustomPids(list: List<CustomPid>)
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
    suspend fun saveDashboards(list: List<Dashboard>)
}
