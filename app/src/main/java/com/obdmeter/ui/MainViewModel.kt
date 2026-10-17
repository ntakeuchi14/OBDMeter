package com.obdmeter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdmeter.core.Pids
import com.obdmeter.data.AppSettings
import com.obdmeter.data.Dashboard
import com.obdmeter.data.Defaults
import com.obdmeter.data.GaugeConfig
import com.obdmeter.data.Repository
import com.obdmeter.session.ConnectionState
import com.obdmeter.session.LogBuffer
import com.obdmeter.session.ObdSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)
    val log = LogBuffer()
    private val session = ObdSession(app, viewModelScope, log)

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val dashboards: StateFlow<List<Dashboard>> =
        repo.dashboards.stateIn(viewModelScope, SharingStarted.Eagerly, Defaults.dashboards)

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
    fun setActiveDashboard(dashboard: Dashboard?) {
        session.activePids = dashboard?.gauges?.mapNotNull { Pids.get(it.pidKey) }?.distinctBy { it.key }.orEmpty()
    }

    // ---- 設定 ----

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.updateSettings(transform) }
    }

    // ---- ダッシュボード編集 ----

    private fun editDashboards(transform: (List<Dashboard>) -> List<Dashboard>) {
        viewModelScope.launch { repo.saveDashboards(transform(dashboards.value)) }
    }

    private fun editDashboard(id: Long, transform: (Dashboard) -> Dashboard) =
        editDashboards { list -> list.map { if (it.id == id) transform(it) else it } }

    fun addDashboard(name: String): Long {
        val id = System.currentTimeMillis()
        editDashboards { it + Dashboard(id = id, name = name, columns = 2) }
        return id
    }

    fun renameDashboard(id: Long, name: String) = editDashboard(id) { it.copy(name = name) }

    fun deleteDashboard(id: Long) = editDashboards { list -> list.filterNot { it.id == id } }

    fun moveDashboard(id: Long, delta: Int) = editDashboards { it.moved(it.indexOfFirst { d -> d.id == id }, delta) }

    fun setColumns(id: Long, columns: Int) = editDashboard(id) { it.copy(columns = columns.coerceIn(1, 4)) }

    fun addGauge(id: Long, gauge: GaugeConfig) = editDashboard(id) { it.copy(gauges = it.gauges + gauge) }

    fun updateGauge(id: Long, index: Int, gauge: GaugeConfig) = editDashboard(id) {
        it.copy(gauges = it.gauges.mapIndexed { i, g -> if (i == index) gauge else g })
    }

    fun removeGauge(id: Long, index: Int) = editDashboard(id) {
        it.copy(gauges = it.gauges.filterIndexed { i, _ -> i != index })
    }

    fun moveGauge(id: Long, index: Int, delta: Int) = editDashboard(id) { it.copy(gauges = it.gauges.moved(index, delta)) }

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
