package com.obdmeter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obdmeter.AppCore
import com.obdmeter.core.PidCatalog
import com.obdmeter.data.AppSettings
import com.obdmeter.data.BackupLogic
import com.obdmeter.data.BackupManifest
import com.obdmeter.data.BackupZip
import com.obdmeter.data.CustomPid
import com.obdmeter.data.Dashboard
import com.obdmeter.data.Defaults
import com.obdmeter.data.FontEntry
import com.obdmeter.data.GaugeConfig
import com.obdmeter.data.GaugeStyle
import com.obdmeter.data.ImportMode
import com.obdmeter.data.Layout
import com.obdmeter.data.LoadedBackup
import com.obdmeter.data.imageNames
import com.obdmeter.session.ConnectionState
import com.obdmeter.session.ObdSession
import com.obdmeter.util.DateFmt
import com.obdmeter.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

class MainViewModel(val core: AppCore) : ViewModel() {
    private val platform = core.platform
    private val repo = core.repo
    val log = core.log
    private val session = core.session

    val settings: StateFlow<AppSettings> = core.settings
    val dashboards: StateFlow<List<Dashboard>> = core.dashboards
    val customPids: StateFlow<List<CustomPid>> = core.customPids

    /** 標準項目 + カスタム PID。式が不正なカスタム PID は一覧から外す */
    val catalog: StateFlow<PidCatalog> = core.catalog

    val connection: StateFlow<ConnectionState> = session.state
    val values: StateFlow<Map<String, Double>> = core.values
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
            val used = repo.dashboards.first().flatMap { it.imageNames() }.toSet()
            runCatching { platform.cleanupImages(used) }
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

    /** バックアップのファイル名の既定 */
    fun backupFileName(): String = "obdmeter_" + DateFmt("yyyyMMdd_HHmm").format(nowMs()) + ".zip"

    /**
     * 設定をバックアップ zip にして [write] で書き出す。[write] は保存先の説明を返す (なければ null)
     */
    fun exportBackup(write: suspend (ByteArray) -> String?) {
        viewModelScope.launch {
            _backupBusy.value = true
            runCatching {
                val manifest = BackupManifest(
                    appVersion = platform.appVersion,
                    exportedAt = DateFmt("yyyy-MM-dd HH:mm").format(nowMs()),
                    dashboards = repo.dashboards.first(),
                    customPids = repo.customPids.first(),
                    fonts = repo.fonts.first(),
                    pollIntervalsMs = repo.settings.first().pollIntervalsMs,
                )
                val (bytes, images) = BackupZip.build(platform, manifest)
                val savedAs = write(bytes)
                "エクスポートしました (ページ ${manifest.dashboards.size}、カスタム PID ${manifest.customPids.size}、" +
                    "画像 $images、フォント ${manifest.fonts.size})" + (savedAs?.let { "\n保存先: $it" } ?: "")
            }.onSuccess { _backupMessage.value = it }
                .onFailure { _backupMessage.value = "エクスポートに失敗しました: ${it.message}" }
            _backupBusy.value = false
        }
    }

    /** [read] で読んだバックアップ zip を、適用方法の選択待ちにする */
    fun loadBackup(read: suspend () -> ByteArray) {
        viewModelScope.launch {
            _backupBusy.value = true
            runCatching { BackupZip.read(read()) }
                .onSuccess { _pendingImport.value = it }
                .onFailure { _backupMessage.value = "読み込めませんでした: ${it.message}" }
            _backupBusy.value = false
        }
    }

    fun showMessage(text: String) { _backupMessage.value = text }

    fun cancelImport() { _pendingImport.value = null }

    fun applyImport(mode: ImportMode) {
        val backup = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            _backupBusy.value = true
            runCatching {
                val rename = BackupZip.saveImages(platform, backup)
                // フォントはライブラリに追加する (置き換えのときも既存のフォントは残す)
                val fontRename = fontLock.withLock {
                    val library = repo.fonts.first()
                    val (map, added) = BackupZip.saveFonts(platform, backup, library)
                    if (added.isNotEmpty()) repo.saveFonts(library + added)
                    map
                }
                var seq = nowMs()
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
                            currentIntervals = repo.settings.first().pollIntervalsMs,
                        )
                        repo.saveCustomPids(result.customPids)
                        repo.saveDashboards(result.dashboards)
                        repo.updateSettings { it.copy(pollIntervalsMs = result.pollIntervalsMs) }
                    }
                }
                val missing = BackupLogic.referencedImages(backup.manifest.dashboards).size - rename.size
                buildString {
                    append(if (mode == ImportMode.REPLACE) "置き換えました" else "追加しました")
                    append(" (ページ ${backup.pageCount}、カスタム PID ${backup.customPidCount}、画像 ${rename.size}、フォント ${fontRename.size}")
                    backup.manifest.pollIntervalsMs?.let { append("、取得周期 ${it.size} 項目") }
                    append(")")
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
    fun importFont(read: suspend () -> Pair<ByteArray, String>, onResult: (Result<FontEntry>) -> Unit) {
        viewModelScope.launch {
            val r = runCatching {
                val (bytes, displayName) = read()
                if (bytes.size > 40 * 1024 * 1024) throw IllegalArgumentException("フォントファイルが大きすぎます (40MB まで)")
                fontLock.withLock {
                    val current = repo.fonts.first()
                    val entry = platform.saveFont(bytes, displayName, current)
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
                list.firstOrNull { it.id == id }?.let { platform.deleteFont(it) }
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
                platform.connectionTest(s, log) { line -> _testLines.value = _testLines.value.orEmpty() + line }
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
                platform.bmwSearch(s, log, targets, ranges) { line -> _testLines.value = _testLines.value.orEmpty() + line }
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
        if (visible) core.cancelIdleDisconnect()
        if (visible && core.reconnectOnReturn) {
            // 見ていない間に切断していた (接続していたのだから、自動接続の設定に関係なくつなぎ直す)
            core.reconnectOnReturn = false
            if (session.state.value == ConnectionState.Disconnected) connect()
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
    /** 保存内容を読み終えたか ([AppCore.ready]) */
    val ready: StateFlow<Boolean> = core.ready

    /** 最初に表示するページの位置 (最後に表示していたページ。無ければ先頭)。読み終えた後に呼ぶ */
    fun initialPageIndex(): Int {
        val id = core.appPage.value ?: settings.value.lastPageId ?: return 0
        return dashboards.value.indexOfFirst { it.id == id }.coerceAtLeast(0)
    }

    suspend fun pageToRestore(): Long? {
        val id = core.appPage.value ?: repo.settings.first().lastPageId ?: return null
        return withTimeoutOrNull(3000) { dashboards.first { list -> list.any { it.id == id } } }?.let { id }
    }

    /** アプリのダッシュボードの 1 マスの縦横比を覚えておく (フロート表示の形に使う) */
    fun reportCellAspect(aspect: Float) { if (aspect.isFinite() && aspect > 0f) core.cellAspect.value = aspect }

    val cellSizeDp: StateFlow<Pair<Float, Float>?> = core.cellSizeDp

    /** ダッシュボードの 1 マスの大きさ (dp) */
    fun reportCellSize(w: Float, h: Float) { if (w > 0f && h > 0f) core.cellSizeDp.value = w to h }

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
        val id = nowMs()
        editDashboards { it + Dashboard(id = id, name = name) }
        return id
    }

    fun renameDashboard(id: Long, name: String) = editDashboard(id) { it.copy(name = name) }

    /** ページの背景を変える */
    fun setPageBackground(id: Long, bg: com.obdmeter.data.PageBackground) = editDashboard(id) { it.copy(background = bg) }

    /** ほかのすべてのページにも同じ背景を使う */
    fun applyBackgroundToAll(bg: com.obdmeter.data.PageBackground) = editDashboards { list -> list.map { it.copy(background = bg) } }

    fun deleteDashboard(id: Long) = editDashboards { list -> list.filterNot { it.id == id } }

    fun moveDashboard(id: Long, delta: Int) = editDashboards { it.moved(it.indexOfFirst { d -> d.id == id }, delta) }

    /** ページ内のメーターを [columns] 列で均等に並べ直す */
    fun arrangeInGrid(id: Long, columns: Int) = editDashboard(id) {
        it.copy(columns = columns, gauges = Layout.arrangeInGrid(it.gauges, columns))
    }

    /** 空いている場所にメーターを追加し、その id を返す */
    fun addGauge(dashboardId: Long, pidKey: String, style: GaugeStyle): Long {
        val gaugeId = nowMs()
        editDashboard(dashboardId) { d ->
            val w = (if (style == GaugeStyle.ANALOG) 8 else 6) * Layout.SCALE
            val h = (if (style == GaugeStyle.ANALOG) 6 else 3) * Layout.SCALE
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

    /** ページを前後に切り替える (Web 版の画面の外の矢印) */
    fun navigatePage(delta: Int) { if (delta != 0) _pageNav.tryEmit(delta) }

    /** キーやダイヤルの入力を受け取る。ページを切り替えたら true */
    fun onNavInput(delta: Int, what: String): Boolean {
        log.log(com.obdmeter.core.CommLogger.Direction.INFO, "入力: $what")
        if (!dashboardKeyNav || !settings.value.keyPageNav || delta == 0) return false
        _pageNav.tryEmit(delta)
        return true
    }

    // ---- コピー・貼り付け (ページをまたいで使えるよう、画面ではなくここで持つ) ----

    private val _clipboard = MutableStateFlow<List<GaugeConfig>?>(null)
    /** コピーしたメーター (グループならまとめて。null = なし) */
    val clipboard: StateFlow<List<GaugeConfig>?> = _clipboard

    /** メーター 1 つだけをコピーする (グループからは外したものとして) */
    fun copyGauge(g: GaugeConfig) { _clipboard.value = listOf(g.copy(layoutGroup = null)) }

    /** グループのメーターをまとめてコピーする */
    fun copyGauges(gs: List<GaugeConfig>) { _clipboard.value = gs.takeIf { it.isNotEmpty() } }

    /**
     * コピーしたメーターをページに貼り付ける (グループは並びを保ったまま、新しいグループとして)。
     * 元と同じ位置が空いていればそこ、空いていなければ空いている場所、どこも空いていなければ 1 マスずらして置く
     */
    fun pasteGauge(dashboardId: Long) {
        val src = _clipboard.value ?: return
        val base = nowMs()
        val group = if (src.size >= 2) base else null
        editDashboard(dashboardId) { d ->
            val (dx, dy) = Layout.pasteOffset(d.gauges, src)
            d.copy(gauges = d.gauges + src.mapIndexed { i, g ->
                g.copy(id = base + i, x = g.x + dx, y = g.y + dy, layoutGroup = group)
            })
        }
    }

    /** 複数のメーターの位置・大きさをまとめて変える (グループの移動・大きさ変更) */
    fun placeGauges(dashboardId: Long, placements: Map<Long, IntArray>) = editDashboard(dashboardId) { d ->
        d.copy(gauges = d.gauges.map { g ->
            placements[g.id]?.let { p -> g.copy(x = p[0], y = p[1], w = p[2], h = p[3]) } ?: g
        })
    }

    /** メーターをまとめて 1 つのグループにする */
    fun groupGauges(dashboardId: Long, ids: Set<Long>) {
        if (ids.size < 2) return
        val group = nowMs()
        editDashboard(dashboardId) { d -> d.copy(gauges = d.gauges.map { if (it.id in ids) it.copy(layoutGroup = group) else it }) }
    }

    /** メーターのグループを解く ([ids] が入っているグループ全体) */
    fun ungroupGauges(dashboardId: Long, ids: Set<Long>) = editDashboard(dashboardId) { d ->
        val groups = d.gauges.filter { it.id in ids }.mapNotNullTo(HashSet()) { it.layoutGroup }
        d.copy(gauges = d.gauges.map { if (it.layoutGroup in groups) it.copy(layoutGroup = null) else it })
    }

    /**
     * 重なりの順番を変える。[delta] = +1 で 1 つ手前、-1 で 1 つ奥、
     * [Int.MAX_VALUE] で最前面、[Int.MIN_VALUE] で最背面
     */
    fun moveZ(dashboardId: Long, gaugeId: Long, delta: Int) = editDashboard(dashboardId) { d ->
        val i = d.gauges.indexOfFirst { it.id == gaugeId }
        if (i < 0) return@editDashboard d
        val list = d.gauges.toMutableList()
        val g = list.removeAt(i)
        val to = when (delta) {
            Int.MAX_VALUE -> list.size
            Int.MIN_VALUE -> 0
            else -> (i + delta).coerceIn(0, list.size)
        }
        list.add(to, g)
        d.copy(gauges = list)
    }

    fun bringToFront(dashboardId: Long, gaugeId: Long) = editDashboard(dashboardId) { d ->
        val g = d.gauges.firstOrNull { it.id == gaugeId } ?: return@editDashboard d
        d.copy(gauges = d.gauges.filterNot { it.id == gaugeId } + g)
    }

    fun resetDashboards() = viewModelScope.launch { repo.saveDashboards(Defaults.dashboards) }

    override fun onCleared() {
        // 画面を閉じた・Android が裏で画面を破棄した。すぐには切断せず、しばらく戻ってこなければ切断する
        // (戻ってきたら再接続する。フロート表示中は接続を続ける)
        if (!core.floatActive.value) core.scheduleIdleDisconnect()
    }
}

private fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
