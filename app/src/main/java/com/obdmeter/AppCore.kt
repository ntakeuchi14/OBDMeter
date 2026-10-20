package com.obdmeter

import kotlinx.coroutines.delay
import com.obdmeter.session.ConnectionState
import android.content.Context
import com.obdmeter.core.PidCatalog
import com.obdmeter.data.AppSettings
import com.obdmeter.data.CustomPid
import com.obdmeter.data.Dashboard
import com.obdmeter.data.Defaults
import com.obdmeter.data.FontEntry
import com.obdmeter.data.HistoryStore
import com.obdmeter.data.ReadPlan
import com.obdmeter.core.DeviceItems
import com.obdmeter.sensors.DeviceSensors
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
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
    /** 折れ線グラフの項目の記録のしかた (間隔・変化したときだけか) */
    @Volatile private var recordRules: Map<ReadPlan.RecordTarget, ReadPlan.RecordRule> = emptyMap()
    /** 端末のセンサー (傾き・加速度など。テスト用の項目) */
    val deviceSensors = DeviceSensors(app, scope)

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val dashboards: StateFlow<List<Dashboard>> =
        repo.dashboards.stateIn(scope, SharingStarted.Eagerly, Defaults.dashboards)
    val customPids: StateFlow<List<CustomPid>> =
        repo.customPids.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val fonts: StateFlow<List<FontEntry>> =
        repo.fonts.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 保存してある設定・ページ・カスタム PID・フォントを読み終えたか。
     * 読み終えるまでは既定の画面 (初期ページ) が見えないよう、画面を出さない
     */
    val ready = MutableStateFlow(false)

    /** 標準項目 + カスタム PID。式が不正なカスタム PID は一覧から外す */
    val catalog: StateFlow<PidCatalog> = customPids
        .map { list -> PidCatalog(list.mapNotNull { runCatching { it.toDef() }.getOrNull() }) }
        .stateIn(scope, SharingStarted.Eagerly, PidCatalog())

    /** メーターに出す値 (車から読んだ値 + 端末のセンサーの値) */
    val values: StateFlow<Map<String, Double>> = combine(session.values, deviceSensors.values) { car, dev ->
        if (dev.isEmpty()) car else car + dev
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** グラフの記録で使っている端末のセンサーの項目 */
    private val recordingDeviceKeys = MutableStateFlow<Set<String>>(emptySet())
    /** 表示中のページで使っている端末のセンサーの項目 */
    private val shownDeviceKeys = MutableStateFlow<Set<String>>(emptySet())

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

    /** 「起動時にフロート表示で始める」をこのプロセスで済ませたか */
    @Volatile var startInFloatDone = false

    /** フロート表示を閉じたときに切断した。次にアプリが前面に来たら再接続する */
    @Volatile var reconnectOnReturn = false

    init {
        // 項目一覧 (約 1,200 項目) を裏で先に作っておく (画面の最初の描画を待たせない)
        scope.launch(kotlinx.coroutines.Dispatchers.Default) { com.obdmeter.core.Pids.get("RPM") }
        scope.launch {
            // 保存内容を読み、上の StateFlow にも反映されるのを待つ (念のため最大 3 秒)
            val d = repo.dashboards.first()
            val s = repo.settings.first()
            val c = repo.customPids.first()
            repo.fonts.first()
            withTimeoutOrNull(3_000) {
                dashboards.first { it == d }
                settings.first { it == s }
                customPids.first { it == c }
            }
            ready.value = true
        }
        // 画面に出ているページ (アプリ / フロート) の項目だけを読み出す
        scope.launch {
            combine(dashboards, catalog, appVisible, appPage, floatPage) { pages, cat, visible, app, float ->
                ReadPlan.activeItems(ReadPlan.shownPages(pages, visible, app, float), cat)
            }.collect { (pids, fallbacks) ->
                // 端末のセンサーの項目は車に問い合わせない
                session.activePids = pids.filter { !DeviceItems.isDevice(it) }
                session.activeFallbacks = fallbacks
                shownDeviceKeys.value = pids.filter { DeviceItems.isDevice(it) }.mapTo(HashSet()) { it.key }
            }
        }
        // 折れ線グラフごとの記録のしかたと保存期間 (記録はグラフごとに別々)
        scope.launch {
            // 保存内容を読み終えるまでは何もしない (初期値のページで古い記録を消さないように)
            ready.first { it }
            var adopted = false
            combine(dashboards, appVisible, appPage, floatPage, settings) { pages, visible, app, float, s ->
                Triple(pages, if (visible) app else float, s.pollIntervalsMs.mapValues { it.value.toLong() })
            }.collect { (pages, shownPage, poll) ->
                if (!adopted) {
                    // 以前の版の、項目ごとに共有していた記録を各グラフへ振り分ける (1 回だけ)
                    adopted = true
                    history.adoptShared(ReadPlan.chartKeys(pages))
                }
                recordRules = ReadPlan.recordRules(pages, shownPage, poll)
                history.retentions = ReadPlan.retentions(pages)
                recordingDeviceKeys.value = recordRules.keys.mapNotNullTo(HashSet()) { t -> t.key.takeIf { it.startsWith("DEV_") } }
            }
        }
        // 項目ごとの取得周期
        scope.launch {
            settings.collect { s -> session.pollIntervals = s.pollIntervalsMs.mapValues { it.value.toLong() } }
        }
        // 端末のセンサーは、表示中か記録中の項目のぶんだけ動かす
        scope.launch {
            combine(shownDeviceKeys, recordingDeviceKeys) { a, b -> a + b }.collect { deviceSensors.setActive(it) }
        }
        // 記録: 接続した直後に全項目を 1 回読み、その後は項目ごとの間隔で読む
        // (表示中のページに無い項目も読む。端末のセンサーの項目は接続していなくても記録する)
        // 「変化したときだけ記録する」項目は、項目の取得周期ごとに値を見て、前回記録した値から変わったときだけ記録する
        scope.launch {
            val nextDue = HashMap<ReadPlan.RecordTarget, Long>()
            val changes = com.obdmeter.data.ChangeRecorder()
            var wasConnected = false
            while (true) {
                delay(ReadPlan.CHANGE_MIN_MS)
                val connected = session.state.value is ConnectionState.Connected
                if (connected && !wasConnected) nextDue.keys.removeAll { !it.key.startsWith("DEV_") }
                wasConnected = connected
                val now = System.currentTimeMillis()
                val rules = recordRules
                // 記録しなくなった項目 (表示中だけのグラフのページを離れた など) は、最後に見た時刻まで書いて終える
                history.storeRows(changes.retain(rules.keys))
                val due = rules.filter { (t, _) -> (nextDue[t] ?: 0L) <= now && (connected || t.key.startsWith("DEV_")) }
                if (due.isEmpty()) continue
                // 同じ項目を複数のグラフで記録するときも、読むのは 1 回
                val dueKeys = due.keys.mapTo(HashSet()) { it.key }
                // 表示中のメーターで読んでいる項目 (と端末のセンサー) は、その最新の値を使う
                val live = values.value
                val activeKeys = session.activePids.mapTo(HashSet()) { it.key } + deviceSensors.values.value.keys
                val (reuse, need) = ReadPlan.splitSample(dueKeys, live, activeKeys)
                val defs = need.filter { !it.startsWith("DEV_") }.mapNotNull { catalog.value.get(it) }
                val got = if (defs.isEmpty() || !connected) emptyMap() else (session.sample(defs) ?: continue)
                val read = reuse + got
                val time = System.currentTimeMillis()
                val rows = HashMap<String, HashMap<String, Double>>()
                val changeRows = ArrayList<com.obdmeter.data.HistoryRow>()
                for ((t, rule) in due) {
                    // 表示していない項目を記録のために読むときは、変化だけの記録でも 1 秒に 1 回まで
                    val ms = if (t.key in need && !t.key.startsWith("DEV_")) maxOf(rule.intervalMs, ReadPlan.CHANGE_SAMPLE_MIN_MS) else rule.intervalMs
                    nextDue[t] = now + ms
                    val v = read[t.key] ?: continue
                    if (rule.onChangeOnly) {
                        // アプリを起動し直した後は、記録の最後の行から続ける
                        if (!changes.has(t)) history.lastRow(t.slot, t.key).let { changes.seed(t, it?.first, it?.second == true) }
                        changeRows += changes.observe(t, v, time, ms)
                    }
                    else rows.getOrPut(t.slot) { HashMap() }[t.key] = v
                }
                history.store(rows, time)
                history.storeRows(changeRows)
            }
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
