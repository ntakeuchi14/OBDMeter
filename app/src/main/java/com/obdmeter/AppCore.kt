package com.obdmeter

import android.content.Context
import com.obdmeter.core.PidCatalog
import com.obdmeter.data.AppSettings
import com.obdmeter.data.CustomPid
import com.obdmeter.data.Dashboard
import com.obdmeter.data.Defaults
import com.obdmeter.data.FontEntry
import com.obdmeter.data.GaugeStyle
import com.obdmeter.data.HistoryStore
import com.obdmeter.data.Repository
import com.obdmeter.session.LogBuffer
import com.obdmeter.session.ObdSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * アプリ全体で 1 つだけ持つ状態 (保存データ・車両との接続)。
 * アプリの画面とフロート表示 (別ウィンドウ) の両方から同じ接続・同じ値を使うため、
 * 画面 (ViewModel) ではなくプロセスに 1 つ置く。
 */
class AppCore private constructor(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repo = Repository(app)
    val log = LogBuffer()
    val session = ObdSession(app, scope, log)
    /** 折れ線グラフ用の値の記録 */
    val history = HistoryStore(app, scope)

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val dashboards: StateFlow<List<Dashboard>> =
        repo.dashboards.stateIn(scope, SharingStarted.Eagerly, Defaults.dashboards)
    val customPids: StateFlow<List<CustomPid>> =
        repo.customPids.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val fonts: StateFlow<List<FontEntry>> =
        repo.fonts.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 標準項目 + カスタム PID。式が不正なカスタム PID は一覧から外す */
    val catalog: StateFlow<PidCatalog> = customPids
        .map { list -> PidCatalog(list.mapNotNull { runCatching { it.toDef() }.getOrNull() }) }
        .stateIn(scope, SharingStarted.Eagerly, PidCatalog())

    /** アプリの画面が前面に出ているか */
    val appVisible = MutableStateFlow(false)
    /** アプリのダッシュボードで表示中のページ */
    val appPage = MutableStateFlow<Long?>(null)
    /** フロート表示中のページ (表示していなければ null) */
    val floatPage = MutableStateFlow<Long?>(null)
    /** フロート表示が有効か (✕ で閉じるまで。アプリが前面にある間は隠れる) */
    val floatActive = MutableStateFlow(false)

    /** アプリのダッシュボードの 1 マスの縦横比 (幅 / 高さ)。フロート表示の形をアプリと合わせるのに使う */
    val cellAspect = MutableStateFlow(defaultCellAspect())

    /** 起動時の自動接続をこのプロセスで済ませたか */
    @Volatile var autoConnectDone = false

    /** フロート表示を閉じたときに切断した。次にアプリが前面に来たら再接続する */
    @Volatile var reconnectOnReturn = false

    init {
        // 画面に出ているページ (アプリ / フロート) の項目だけを読み出す
        scope.launch {
            combine(dashboards, catalog, appVisible, appPage, floatPage) { pages, cat, visible, app, float ->
                val shown = listOfNotNull(
                    if (visible) app else null,
                    if (!visible) float else null,
                ).mapNotNull { id -> pages.firstOrNull { it.id == id } }
                    // まだ何も表示していないとき (起動直後) はアプリのページ
                    .ifEmpty { listOfNotNull(pages.firstOrNull { it.id == app }) }
                val gauges = shown.flatMap { it.gauges }
                // 折れ線グラフは追加した項目も読む
                gauges.flatMap { g -> if (g.style == GaugeStyle.CHART) g.chartAllKeys else listOf(g.pidKey) }
                    .mapNotNull { cat.get(it) }.distinctBy { it.key } to
                    gauges.mapNotNull { g -> g.fallbackKey?.let { fb -> cat.get(fb)?.let { g.pidKey to it } } }.toMap()
            }.collect { (pids, fallbacks) ->
                session.activePids = pids
                session.activeFallbacks = fallbacks
            }
        }
        // 折れ線グラフで使っている項目を記録する (保存日数はグラフの設定の最大)
        scope.launch {
            dashboards.collect { pages ->
                val charts = pages.flatMap { it.gauges }.filter { it.style == GaugeStyle.CHART }
                history.keys = charts.flatMap { it.chartAllKeys }.toSet()
                history.retentionDays = charts.maxOfOrNull { it.design.historyDays } ?: 1
            }
        }
        scope.launch {
            session.values.collect { v -> if (v.isNotEmpty()) history.record(v) }
        }
    }

    private fun defaultCellAspect(): Float {
        val m = app.resources.displayMetrics
        val long = maxOf(m.widthPixels, m.heightPixels).toFloat()
        val short = minOf(m.widthPixels, m.heightPixels).toFloat()
        // 横向きで使う前提: 24 列 × 12 行
        return (long / 24f) / (short / 12f)
    }

    companion object {
        @Volatile private var instance: AppCore? = null

        fun get(context: Context): AppCore =
            instance ?: synchronized(this) { instance ?: AppCore(context).also { instance = it } }
    }
}
