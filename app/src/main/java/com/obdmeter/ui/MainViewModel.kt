package com.obdmeter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdmeter.core.Pids
import com.obdmeter.data.AppSettings
import com.obdmeter.core.PidCatalog
import com.obdmeter.data.CustomPid
import com.obdmeter.data.Dashboard
import com.obdmeter.data.Defaults
import com.obdmeter.data.GaugeConfig
import com.obdmeter.data.GaugeStyle
import com.obdmeter.data.Layout
import com.obdmeter.data.Repository
import com.obdmeter.session.ConnectionState
import com.obdmeter.session.LogBuffer
import com.obdmeter.session.ObdSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)
    val log = LogBuffer()
    private val session = ObdSession(app, viewModelScope, log)

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val dashboards: StateFlow<List<Dashboard>> =
        repo.dashboards.stateIn(viewModelScope, SharingStarted.Eagerly, Defaults.dashboards)

    val customPids: StateFlow<List<CustomPid>> =
        repo.customPids.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 標準項目 + カスタム PID。式が不正なカスタム PID は一覧から外す */
    val catalog: StateFlow<PidCatalog> = customPids
        .map { list -> PidCatalog(list.mapNotNull { runCatching { it.toDef() }.getOrNull() }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PidCatalog())

    val connection: StateFlow<ConnectionState> = session.state
    val values: StateFlow<Map<String, Double>> = session.values
    val rate: StateFlow<Double> = session.rate
    val supported: StateFlow<Set<Int>?> = session.supported

    // ---- 接続 ----

    fun connect() {
        viewModelScope.launch {
            // DataStore の初回読み込みを待ってから最新設定で接続
            val s = repo.settings.first()
            session.connect(s)
        }
    }

    fun disconnect() = session.disconnect()

    /** 表示中のダッシュボードが変わったら、読み出す項目を切り替える */
    fun setActiveDashboard(dashboard: Dashboard?, catalog: PidCatalog) {
        session.activePids = dashboard?.gauges?.mapNotNull { catalog.get(it.pidKey) }?.distinctBy { it.key }.orEmpty()
    }

    // ---- カスタム PID ----

    private val customLock = Mutex()

    private fun editCustomPids(transform: (List<CustomPid>) -> List<CustomPid>) {
        viewModelScope.launch { customLock.withLock { repo.saveCustomPids(transform(repo.customPids.first())) } }
    }

    /** 追加または更新 (id が同じものを置き換える) */
    fun saveCustomPid(pid: CustomPid) = editCustomPids { list ->
        if (list.any { it.id == pid.id }) list.map { if (it.id == pid.id) pid else it } else list + pid
    }

    /** カスタム PID を削除し、それを使っているメーターも外す */
    fun deleteCustomPid(id: Long) {
        val key = CustomPid.KEY_PREFIX + id
        editCustomPids { list -> list.filterNot { it.id == id } }
        editDashboards { list -> list.map { d -> d.copy(gauges = d.gauges.filterNot { it.pidKey == key }) } }
    }

    /** 編集中のカスタム PID を、接続中の車両から 1 回だけ読んでみる */
    suspend fun testCustomPid(pid: CustomPid): ObdSession.TestResult {
        val def = runCatching { pid.toDef() }.getOrElse { return ObdSession.TestResult(null, it.message) }
        return session.testQuery(def.request!!)
    }

    // ---- 設定 ----

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.updateSettings(transform) }
    }

    // ---- ダッシュボード編集 ----

    private val editLock = Mutex()

    /**
     * 編集は保存済みの最新状態に対して 1 件ずつ順番に適用する。
     * (ドラッグ直後に別の編集をしても、古い状態で上書きしないように)
     */
    private fun editDashboards(transform: (List<Dashboard>) -> List<Dashboard>) {
        viewModelScope.launch {
            editLock.withLock { repo.saveDashboards(transform(repo.dashboards.first())) }
        }
    }

    private fun editDashboard(id: Long, transform: (Dashboard) -> Dashboard) =
        editDashboards { list -> list.map { if (it.id == id) transform(it) else it } }

    fun addDashboard(name: String): Long {
        val id = System.currentTimeMillis()
        editDashboards { it + Dashboard(id = id, name = name) }
        return id
    }

    fun renameDashboard(id: Long, name: String) = editDashboard(id) { it.copy(name = name) }

    fun deleteDashboard(id: Long) = editDashboards { list -> list.filterNot { it.id == id } }

    fun moveDashboard(id: Long, delta: Int) = editDashboards { it.moved(it.indexOfFirst { d -> d.id == id }, delta) }

    /** ページ内のメーターを [columns] 列で均等に並べ直す */
    fun arrangeInGrid(id: Long, columns: Int) = editDashboard(id) {
        it.copy(columns = columns, gauges = Layout.arrangeInGrid(it.gauges, columns))
    }

    /** 空いている場所にメーターを追加し、その id を返す */
    fun addGauge(dashboardId: Long, pidKey: String, style: GaugeStyle): Long {
        val gaugeId = System.currentTimeMillis()
        editDashboard(dashboardId) { d ->
            val w = if (style == GaugeStyle.ANALOG) 8 else 6
            val h = if (style == GaugeStyle.ANALOG) 6 else 3
            val (x, y) = Layout.findFreeSpot(d.gauges, w, h)
            d.copy(gauges = d.gauges + GaugeConfig(pidKey, style, gaugeId, x, y, w, h))
        }
        return gaugeId
    }

    fun updateGauge(dashboardId: Long, gaugeId: Long, transform: (GaugeConfig) -> GaugeConfig) =
        editDashboard(dashboardId) { d ->
            d.copy(gauges = d.gauges.map { if (it.id == gaugeId) transform(it) else it })
        }

    fun removeGauge(dashboardId: Long, gaugeId: Long) = editDashboard(dashboardId) { d ->
        d.copy(gauges = d.gauges.filterNot { it.id == gaugeId })
    }

    /** 重なったときに手前に表示する (リストの最後が最前面) */
    fun bringToFront(dashboardId: Long, gaugeId: Long) = editDashboard(dashboardId) { d ->
        val g = d.gauges.firstOrNull { it.id == gaugeId } ?: return@editDashboard d
        d.copy(gauges = d.gauges.filterNot { it.id == gaugeId } + g)
    }

    fun resetDashboards() = viewModelScope.launch { repo.saveDashboards(Defaults.dashboards) }

    override fun onCleared() {
        session.disconnect()
    }
}

private fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
