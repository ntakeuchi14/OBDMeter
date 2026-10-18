package com.obdmeter.ui

import android.app.Application
import android.net.Uri
import com.obdmeter.data.imageNames
import com.obdmeter.data.BackupFiles
import com.obdmeter.data.BackupLogic
import com.obdmeter.data.BackupManifest
import com.obdmeter.data.ImportMode
import com.obdmeter.data.LoadedBackup
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdmeter.AppCore
import com.obdmeter.core.Pids
import com.obdmeter.data.AppSettings
import com.obdmeter.core.PidCatalog
import com.obdmeter.data.CustomPid
import com.obdmeter.data.Dashboard
import com.obdmeter.data.Defaults
import com.obdmeter.data.GaugeConfig
import com.obdmeter.data.GaugeImages
import com.obdmeter.data.FontEntry
import com.obdmeter.data.GaugeFonts
import com.obdmeter.data.GaugeStyle
import com.obdmeter.data.Layout
import com.obdmeter.data.Repository
import com.obdmeter.session.ConnectionState
import com.obdmeter.session.ConnectionTest
import com.obdmeter.session.LogBuffer
import com.obdmeter.session.ObdSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val core = AppCore.get(app)
    private val repo = core.repo
    val log = core.log
    private val session = core.session

    val settings: StateFlow<AppSettings> = core.settings
    val dashboards: StateFlow<List<Dashboard>> = core.dashboards
    val customPids: StateFlow<List<CustomPid>> = core.customPids

    /** 標準項目 + カスタム PID。式が不正なカスタム PID は一覧から外す */
    val catalog: StateFlow<PidCatalog> = core.catalog

    val connection: StateFlow<ConnectionState> = session.state
    val values: StateFlow<Map<String, Double>> = session.values
    val rate: StateFlow<Double> = session.rate
    val supported: StateFlow<Set<Int>?> = session.supported
    val floatActive: StateFlow<Boolean> = core.floatActive
    /** ダッシュボードの 1 マスの縦横比 (幅 / 高さ) */
    val cellAspect: StateFlow<Float> = core.cellAspect
    /** 折れ線グラフ用の記録 */
    val history = core.history

    init {
        // 起動時の自動接続 (ViewModel は画面回転などでは作り直されないので、アプリ起動時の 1 回だけ)
        if (!core.autoConnectDone) {
            core.autoConnectDone = true
            viewModelScope.launch {
                val s = repo.settings.first()
                if (s.autoConnectEffective && session.state.value == ConnectionState.Disconnected) session.connect(s)
            }
        }
        // どのメーターからも使われなくなった背景画像を消す
        viewModelScope.launch {
            val used = repo.dashboards.first().flatMap { it.gauges }.flatMap { it.design.imageNames() }.toSet()
            runCatching { GaugeImages.cleanup(app, used) }
        }
    }

    // ---- バックアップ (エクスポート / インポート) ----

    /** 結果の通知 (画面でスナックバー表示などに使う) */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage

    /** 読み込み済みで、適用方法の選択待ちのバックアップ */
    private val _pendingImport = MutableStateFlow<LoadedBackup?>(null)
    val pendingImport: StateFlow<LoadedBackup?> = _pendingImport

    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy

    fun clearBackupMessage() { _backupMessage.value = null }

    /**
     * 保存先を選ぶ画面が無い端末 (車載ナビなど) 用: ダウンロードフォルダ (Download/OBDMeter) に直接書き出す
     */
    fun exportBackupToDownloads(name: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val target = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/OBDMeter")
                        }
                        val uri = app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("保存先を作れませんでした")
                        uri to "Download/OBDMeter/$name"
                    } else {
                        val dir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
                        val f = java.io.File(dir, name)
                        Uri.fromFile(f) to f.absolutePath
                    }
                }
            }.getOrElse {
                _backupMessage.value = "エクスポートに失敗しました: ${it.message}"
                return@launch
            }
            exportBackup(target.first, target.second)
        }
    }

    fun exportBackup(uri: Uri, savedAs: String? = null) {
        viewModelScope.launch {
            _backupBusy.value = true
            runCatching {
                val app = getApplication<Application>()
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val manifest = BackupManifest(
                    appVersion = version ?: "",
                    exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(Date()),
                    dashboards = repo.dashboards.first(),
                    customPids = repo.customPids.first(),
                    fonts = repo.fonts.first(),
                )
                val images = BackupFiles.export(app, uri, manifest)
                "エクスポートしました (ページ ${manifest.dashboards.size}、カスタム PID ${manifest.customPids.size}、" +
                    "画像 $images、フォント ${manifest.fonts.size})" + (savedAs?.let { "\n保存先: $it" } ?: "")
            }.onSuccess { _backupMessage.value = it }
                .onFailure { _backupMessage.value = "エクスポートに失敗しました: ${it.message}" }
            _backupBusy.value = false
        }
    }

    fun loadBackup(uri: Uri) {
        viewModelScope.launch {
            _backupBusy.value = true
            runCatching { BackupFiles.read(getApplication(), uri) }
                .onSuccess { _pendingImport.value = it }
                .onFailure { _backupMessage.value = "読み込めませんでした: ${it.message}" }
            _backupBusy.value = false
        }
    }

    fun cancelImport() { _pendingImport.value = null }

    fun applyImport(mode: ImportMode) {
        val backup = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            _backupBusy.value = true
            runCatching {
                val rename = BackupFiles.saveImages(getApplication(), backup)
                // フォントはライブラリに追加する (置き換えのときも既存のフォントは残す)
                val fontRename = fontLock.withLock {
                    val library = repo.fonts.first()
                    val (map, added) = BackupFiles.saveFonts(getApplication(), backup, library)
                    if (added.isNotEmpty()) repo.saveFonts(library + added)
                    map
                }
                var seq = System.currentTimeMillis()
                // 編集中の保存と競合しないよう、両方のロックを取ってから書く
                editLock.withLock {
                    customLock.withLock {
                        val result = BackupLogic.apply(
                            current = repo.dashboards.first(),
                            currentPids = repo.customPids.first(),
                            incoming = backup.manifest,
                            imageRename = rename,
                            mode = mode,
                            newId = { seq++ },
                            fontRename = fontRename,
                        )
                        repo.saveCustomPids(result.customPids)
                        repo.saveDashboards(result.dashboards)
                    }
                }
                val missing = BackupLogic.referencedImages(backup.manifest.dashboards).size - rename.size
                buildString {
                    append(if (mode == ImportMode.REPLACE) "置き換えました" else "追加しました")
                    append(" (ページ ${backup.pageCount}、カスタム PID ${backup.customPidCount}、画像 ${rename.size}、フォント ${fontRename.size})")
                    if (missing > 0) append("。画像 $missing 枚はファイルに含まれていなかったため外しました")
                }
            }.onSuccess { _backupMessage.value = it }
                .onFailure { _backupMessage.value = "インポートに失敗しました: ${it.message}" }
            _backupBusy.value = false
        }
    }

    // ---- フォント ----

    val fonts: StateFlow<List<FontEntry>> = core.fonts
    private val fontLock = Mutex()

    /** フォントファイルを取り込み、ライブラリに追加する。結果 (id かエラー) を [onResult] に返す */
    fun importFont(uri: Uri, onResult: (Result<FontEntry>) -> Unit) {
        viewModelScope.launch {
            val r = runCatching {
                fontLock.withLock {
                    val current = repo.fonts.first()
                    val entry = GaugeFonts.import(getApplication(), uri, current)
                    if (current.none { it.id == entry.id }) repo.saveFonts(current + entry)
                    entry
                }
            }
            onResult(r)
        }
    }

    fun renameFont(id: String, name: String) {
        viewModelScope.launch {
            fontLock.withLock {
                repo.saveFonts(repo.fonts.first().map { if (it.id == id) it.copy(name = name) else it })
            }
        }
    }

    /** フォントを削除する。使っていたメーターは既定のフォントに戻す */
    fun deleteFont(id: String) {
        viewModelScope.launch {
            fontLock.withLock {
                val list = repo.fonts.first()
                list.firstOrNull { it.id == id }?.let { GaugeFonts.delete(getApplication(), it) }
                repo.saveFonts(list.filterNot { it.id == id })
            }
        }
        editDashboards { pages ->
            pages.map { d ->
                d.copy(gauges = d.gauges.map { g ->
                    if (g.design.valueFont == id || g.design.labelFont == id) {
                        g.copy(design = g.design.copy(
                            valueFont = g.design.valueFont.takeIf { it != id },
                            labelFont = g.design.labelFont.takeIf { it != id },
                        ))
                    } else g
                })
            }
        }
    }

    // ---- 接続テスト ----

    private val _testLines = MutableStateFlow<List<String>?>(null)
    /** 接続テストの結果 (null = ダイアログを閉じている) */
    val testLines: StateFlow<List<String>?> = _testLines
    private val _testRunning = MutableStateFlow(false)
    val testRunning: StateFlow<Boolean> = _testRunning

    fun runConnectionTest() {
        if (_testRunning.value) return
        viewModelScope.launch {
            _testRunning.value = true
            _testLines.value = emptyList()
            // 接続中ならいったん切断し、USB を閉じてからテストする
            session.disconnectAndWait()
            val s = repo.settings.first()
            runCatching {
                ConnectionTest(getApplication(), log).run(s) { line -> _testLines.value = _testLines.value.orEmpty() + line }
            }.onFailure { _testLines.value = _testLines.value.orEmpty() + "✗ エラー: ${it.message}" }
            _testRunning.value = false
        }
    }

    fun closeConnectionTest() { if (!_testRunning.value) _testLines.value = null }

    private var testJob: kotlinx.coroutines.Job? = null

    /** BMW の値の探索 (BimmerLink の値がどの ID にあるかを調べる) */
    fun runBmwSearch(targets: List<Long>, ranges: List<Pair<Int, IntRange>>) {
        if (_testRunning.value || targets.isEmpty()) return
        testJob = viewModelScope.launch {
            _testRunning.value = true
            _testLines.value = emptyList()
            session.disconnectAndWait()
            val s = repo.settings.first()
            runCatching {
                ConnectionTest(getApplication(), log).searchBmw(s, targets, ranges) { line -> _testLines.value = _testLines.value.orEmpty() + line }
            }.onFailure { e ->
                _testLines.value = _testLines.value.orEmpty() +
                    (if (e is kotlinx.coroutines.CancellationException) "中止しました" else "✗ エラー: ${e.message}")
            }
            _testRunning.value = false
        }
    }

    fun stopTest() {
        testJob?.cancel()
        _testRunning.value = false
    }

    // ---- 接続 ----

    fun connect() {
        viewModelScope.launch {
            // DataStore の初回読み込みを待ってから最新設定で接続
            val s = repo.settings.first()
            session.connect(s)
        }
    }

    fun disconnect() {
        core.reconnectOnReturn = false
        session.disconnect()
    }

    /** アプリの画面が前面に出た / 隠れた (フロート表示の出し入れに使う) */
    fun setAppVisible(visible: Boolean) {
        core.appVisible.value = visible
        if (visible && core.reconnectOnReturn) {
            core.reconnectOnReturn = false
            val s = settings.value
            if (s.autoConnectEffective && session.state.value == ConnectionState.Disconnected) {
                session.connect(s)
            }
        }
    }

    /** 表示中のページが変わったら、読み出す項目を切り替える */
    fun setActivePage(id: Long?) {
        core.appPage.value = id
        // 次の起動時にこのページから始められるように覚えておく
        if (id != null) viewModelScope.launch {
            if (repo.settings.first().lastPageId != id) repo.updateSettings { it.copy(lastPageId = id) }
        }
    }

    /**
     * ダッシュボードを開いたときに表示するページ: このプロセスで表示中だったページ、
     * 無ければ前回の起動で最後に表示していたページ。ページの一覧が読み込まれるまで待つ
     */
    suspend fun pageToRestore(): Long? {
        val id = core.appPage.value ?: repo.settings.first().lastPageId ?: return null
        return withTimeoutOrNull(3000) { dashboards.first { list -> list.any { it.id == id } } }?.let { id }
    }

    /** アプリのダッシュボードの 1 マスの縦横比を覚えておく (フロート表示の形に使う) */
    fun reportCellAspect(aspect: Float) { if (aspect.isFinite() && aspect > 0f) core.cellAspect.value = aspect }

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
        editDashboards { list ->
            list.map { d ->
                d.copy(gauges = d.gauges.filterNot { it.pidKey == key }
                    .map { if (it.fallbackKey == key) it.copy(fallbackKey = null) else it })
            }
        }
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
    // ---- iDrive・方向キーでのページ切替 ----

    /** ダッシュボードが表示中で、レイアウト編集中でない (キーでページを切り替えてよい) */
    @Volatile var dashboardKeyNav = false
    private val _pageNav = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 16)
    /** ページ移動の要求 (-1 = 前、+1 = 次) */
    val pageNav: kotlinx.coroutines.flow.SharedFlow<Int> = _pageNav

    /** キーやダイヤルの入力を受け取る。ページを切り替えたら true */
    fun onNavInput(delta: Int, what: String): Boolean {
        log.log(com.obdmeter.core.CommLogger.Direction.INFO, "入力: $what")
        if (!dashboardKeyNav || !settings.value.keyPageNav || delta == 0) return false
        _pageNav.tryEmit(delta)
        return true
    }

    // ---- コピー・貼り付け (ページをまたいで使えるよう、画面ではなくここで持つ) ----

    private val _clipboard = MutableStateFlow<GaugeConfig?>(null)
    /** コピーしたメーター (null = なし) */
    val clipboard: StateFlow<GaugeConfig?> = _clipboard

    fun copyGauge(g: GaugeConfig) { _clipboard.value = g }

    /**
     * コピーしたメーターをページに貼り付ける。元と同じ位置が空いていればそこ、
     * 空いていなければ空いている場所、どこも空いていなければ 1 マスずらして置く
     */
    fun pasteGauge(dashboardId: Long) {
        val src = _clipboard.value ?: return
        val newId = System.currentTimeMillis()
        editDashboard(dashboardId) { d ->
            fun free(x: Int, y: Int) = d.gauges.none { g ->
                x < g.x + g.w && g.x < x + src.w && y < g.y + g.h && g.y < y + src.h
            }
            fun fits(x: Int, y: Int) = x + src.w <= Layout.COLS && y + src.h <= Layout.ROWS
            val (x, y) = when {
                free(src.x, src.y) -> src.x to src.y
                else -> {
                    val spot = Layout.findFreeSpot(d.gauges, src.w, src.h)
                    if (free(spot.first, spot.second) && fits(spot.first, spot.second)) spot
                    else (src.x + 1).coerceAtMost(Layout.COLS - src.w).coerceAtLeast(0) to
                        (src.y + 1).coerceAtMost(Layout.ROWS - src.h).coerceAtLeast(0)
                }
            }
            d.copy(gauges = d.gauges + src.copy(id = newId, x = x, y = y))
        }
    }

    fun bringToFront(dashboardId: Long, gaugeId: Long) = editDashboard(dashboardId) { d ->
        val g = d.gauges.firstOrNull { it.id == gaugeId } ?: return@editDashboard d
        d.copy(gauges = d.gauges.filterNot { it.id == gaugeId } + g)
    }

    fun resetDashboards() = viewModelScope.launch { repo.saveDashboards(Defaults.dashboards) }

    override fun onCleared() {
        // フロート表示中は接続を続ける
        if (!core.floatActive.value) session.disconnect()
    }
}

private fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
