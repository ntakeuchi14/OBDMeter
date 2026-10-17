package com.obdmeter.session

import android.content.Context
import com.obdmeter.core.BmwFastProtocol
import com.obdmeter.core.CommLogger
import com.obdmeter.core.Elm327Protocol
import com.obdmeter.core.ObdProtocol
import com.obdmeter.core.ObdReader
import com.obdmeter.core.PidDef
import com.obdmeter.core.PidRequest
import com.obdmeter.core.SimulatorProtocol
import com.obdmeter.data.AppSettings
import com.obdmeter.data.ConnectionType
import com.obdmeter.data.UsbAdapter
import com.obdmeter.usb.UsbSerialTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val message: String) : ConnectionState
    /**
     * 車両と接続中。[ignitionOff] は ECU が応答しているがエンジンの値を返さない状態
     * (イグニッション OFF で 7F 01 22 を返している)。イグニッションを ON にすれば値が出る。
     */
    data class Connected(val description: String, val ignitionOff: Boolean = false) : ConnectionState
    /** アダプターには接続済みで、車両 (エンジン ECU) の応答を待っている (イグニッション OFF など) */
    data class WaitingForVehicle(val adapter: String, val voltage: Double?) : ConnectionState
    data class Error(val message: String, val retrying: Boolean) : ConnectionState
}

/**
 * アダプターへの接続と、表示中メーター項目の繰り返し読み出しを管理する。
 * 通信はすべて Dispatchers.IO 上のブロッキング呼び出しで行う。
 */
class ObdSession(
    private val context: Context,
    private val scope: CoroutineScope,
    val log: LogBuffer,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val values: StateFlow<Map<String, Double>> = _values.asStateFlow()

    /** 1 秒あたりの読み出し回数 (PID 単位) */
    private val _rate = MutableStateFlow(0.0)
    val rate: StateFlow<Double> = _rate.asStateFlow()

    /** 車両が対応している PID (null = 不明) */
    private val _supported = MutableStateFlow<Set<Int>?>(null)
    val supported: StateFlow<Set<Int>?> = _supported.asStateFlow()

    /** いま画面に出ている項目。ページを切り替えるとここが更新され、読む PID が変わる。 */
    @Volatile
    var activePids: List<PidDef> = emptyList()

    /** 表示中のメーターの代替項目 (メイン項目のキー → 代替項目)。メインが読めないときだけ読む */
    @Volatile
    var activeFallbacks: Map<String, PidDef> = emptyMap()

    private var job: Job? = null

    /** カスタム PID 編集画面の「車両から読む」用。ポーリングの合間に 1 件ずつ処理する */
    private val testQueue = ConcurrentLinkedQueue<Pair<PidRequest, CompletableDeferred<TestResult>>>()

    data class TestResult(val data: IntArray?, val error: String?)

    suspend fun testQuery(request: PidRequest): TestResult {
        val st = _state.value
        if (st !is ConnectionState.Connected && st !is ConnectionState.WaitingForVehicle) {
            return TestResult(null, "未接続です。先にダッシュボードで「接続」してください")
        }
        val d = CompletableDeferred<TestResult>()
        testQueue.add(request to d)
        return withTimeoutOrNull(8000) { d.await() } ?: TestResult(null, "タイムアウトしました")
    }

    private fun drainTests(p: ObdProtocol) {
        while (true) {
            val (req, d) = testQueue.poll() ?: return
            log.log(CommLogger.Direction.INFO, "テスト読み出し: $req")
            val data = runCatching { p.query(req) }.getOrNull()
            d.complete(TestResult(data, if (data == null) p.lastFailure ?: "応答なし" else null))
        }
    }

    fun connect(settings: AppSettings) {
        val old = job
        disconnect()
        job = scope.launch(Dispatchers.IO) {
            // 前のセッションが USB を閉じ終わるまで待つ
            old?.join()
            var attempt = 0
            while (isActive) {
                attempt++
                val end = runSession(settings)
                if (!isActive || end == SessionEnd.STOP) break
                if (!settings.autoReconnect || end == SessionEnd.NO_ADAPTER && attempt >= 50) break
                delay(3000)
            }
        }
    }

    /** 切断して、USB を閉じ終わるまで待つ (接続テストの前など) */
    suspend fun disconnectAndWait() {
        val j = job
        disconnect()
        j?.join()
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _state.value = ConnectionState.Disconnected
        _values.value = emptyMap()
        _rate.value = 0.0
    }

    private enum class SessionEnd {
        /** アダプターに接続できなかった (再試行回数に上限あり) */
        NO_ADAPTER,
        /** 接続できていたが途中で切れた (再接続する) */
        LOST,
        /** 再接続しない (待機時間切れなど) */
        STOP,
    }

    /** 待機を打ち切り、再接続もしないための例外 */
    private class StopSession(message: String) : Exception(message)

    /** 1 回分の接続〜切断 */
    private suspend fun runSession(settings: AppSettings): SessionEnd {
        var protocol: ObdProtocol? = null
        var adapterReady = false
        try {
            _state.value = ConnectionState.Connecting("アダプターを探しています…")
            val p = createProtocol(settings)
            protocol = p
            _state.value = ConnectionState.Connecting("アダプターを初期化しています…")
            val adapter = p.initAdapter()
            adapterReady = true
            log.log(CommLogger.Direction.INFO, "アダプター: $adapter")

            while (currentCoroutineContext().isActive) {
                // ---- 車両 (エンジン ECU) が応答するまで待つ ----
                val vehicle = waitForVehicle(p, adapter, settings)
                log.log(CommLogger.Direction.INFO, "車両に接続: $vehicle")
                // ---- メーター表示 (車両の応答が途絶えたら戻ってくる) ----
                pollVehicle(p, "$adapter / $vehicle")
                if (!settings.waitForVehicle) {
                    throw IllegalStateException("車両からの応答が途絶えました (イグニッション OFF?)")
                }
                log.log(CommLogger.Direction.INFO, "車両からの応答が途絶えました。イグニッション ON を待ちます")
            }
            return SessionEnd.LOST
        } catch (e: CancellationException) {
            throw e
        } catch (e: StopSession) {
            log.log(CommLogger.Direction.INFO, e.message ?: "")
            _state.value = ConnectionState.Error(e.message ?: "", retrying = false)
            _values.value = emptyMap()
            _rate.value = 0.0
            return SessionEnd.STOP
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            log.log(CommLogger.Direction.ERROR, msg)
            _state.value = ConnectionState.Error(msg, retrying = settings.autoReconnect)
            _values.value = emptyMap()
            _rate.value = 0.0
            return if (adapterReady) SessionEnd.LOST else SessionEnd.NO_ADAPTER
        } finally {
            withContext(NonCancellable) { runCatching { protocol?.close() } }
        }
    }

    /**
     * 車両が応答するまで 5 秒ごとに確認する。待機中はアダプターで測ったバッテリー電圧を表示する。
     * 車両の説明文を返す。
     */
    private suspend fun waitForVehicle(p: ObdProtocol, adapter: String, settings: AppSettings): String {
        val start = System.currentTimeMillis()
        var first = true
        while (true) {
            drainTests(p)
            if (first) _state.value = ConnectionState.Connecting("車両と通信を確立しています…")
            p.connectVehicle()?.let { return it }
            val reason = p.lastFailure ?: "応答なし"
            if (!settings.waitForVehicle) {
                throw IllegalStateException("車両に接続できません: $reason (イグニッション ON を確認してください)")
            }
            if (first) log.log(CommLogger.Direction.INFO, "車両が応答しません ($reason)。イグニッション ON を待ちます")
            first = false

            // 待機中もバッテリー電圧だけは表示する (アダプターが測るので車両は起こさない)
            val volt = runCatching { p.readAdapterVoltage() }.getOrNull()
            _values.value = volt?.let { mapOf("BATTERY" to it) } ?: emptyMap()
            _rate.value = 0.0
            _state.value = ConnectionState.WaitingForVehicle(adapter, volt)

            val limitMin = settings.waitTimeoutMin
            if (limitMin > 0 && System.currentTimeMillis() - start > limitMin * 60_000L) {
                throw StopSession("$limitMin 分間車両が応答しなかったため、バッテリー保護のため待機を終了しました")
            }
            // 待機中の問い合わせ間隔。短すぎると車両のネットワークを起こし続けてしまう
            repeat(25) { delay(200); drainTests(p) }
        }
    }

    /** メーター表示中の読み出しループ。車両の応答が途絶えたら戻る */
    private suspend fun pollVehicle(p: ObdProtocol, desc: String) {
        val reader = ObdReader(p, log)
        _state.value = ConnectionState.Connecting("対応項目を確認しています…")
        reader.detectSupported()
        _supported.value = reader.supported
        // 対応 PID 一覧も拒否された (7F 01 22) なら、イグニッション OFF で接続している
        var ignitionOff = reader.supported == null && p.lastResponded
        _state.value = ConnectionState.Connected(desc, ignitionOff)

        val values = HashMap<String, Double>()
        var deadCycles = 0
        var count = 0
        var windowStart = System.currentTimeMillis()
        var lastPublish = 0L
        while (currentCoroutineContext().isActive) {
            drainTests(p)
            val defs = activePids
            if (defs.isEmpty()) { delay(100); continue }
            val r = reader.poll(defs, values, activeFallbacks)
            // 画面への反映は 10 回/秒まで (それ以上は描画負荷が増えるだけ)
            val t = System.currentTimeMillis()
            if (t - lastPublish >= 100) {
                _values.value = HashMap(values)
                lastPublish = t
            }
            // 「非対応」から実際に読めた項目へ昇格した場合に画面へ反映
            if (reader.supported != _supported.value) _supported.value = reader.supported
            count += r.ok + r.failed
            // ECU が拒否 (7F) しているだけなら車両とはつながっている。完全に無応答が続いたときだけ切れたとみなす
            deadCycles = if (!r.vehicleAlive && r.failed > 0) deadCycles + 1 else 0
            if (deadCycles >= 5) {
                _values.value = emptyMap()
                _rate.value = 0.0
                return
            }
            // イグニッション OFF (ECU は応答するが値を返さない) の判定
            // 判定は標準 PID だけで行う (カスタム PID はイグニッション OFF でも読めるため)
            val nowOff = r.ignitionOff
            val anyMode01Ok = r.stdOk > 0
            if (nowOff != ignitionOff && (nowOff || anyMode01Ok)) {
                ignitionOff = nowOff
                log.log(CommLogger.Direction.INFO,
                    if (ignitionOff) "ECU は応答していますが値を返しません (イグニッション OFF)" else "イグニッション ON を検出しました")
                if (!ignitionOff && reader.supported == null) {
                    // イグニッション OFF のまま接続したので、対応 PID 一覧を取り直す
                    reader.detectSupported()
                    _supported.value = reader.supported
                }
                _state.value = ConnectionState.Connected(desc, ignitionOff)
            }
            if (ignitionOff) {
                // 値が出ない間は問い合わせを 1 秒に 1 回に抑える (車両のネットワークへの負担とバッテリー対策)
                _rate.value = 0.0
                repeat(5) { delay(200); drainTests(p) }
                continue
            }
            val now = System.currentTimeMillis()
            if (now - windowStart >= 1000) {
                _rate.value = count * 1000.0 / (now - windowStart)
                count = 0
                windowStart = now
            }
            if (r.ok + r.failed == 0) delay(20) // 何も読まなかったサイクルで CPU を回し続けない
        }
    }

    private suspend fun createProtocol(settings: AppSettings): ObdProtocol {
        if (settings.connectionType == ConnectionType.SIMULATOR) return SimulatorProtocol()

        val drivers = UsbSerialTransport.findDrivers(context)
        val driver = drivers.firstOrNull()
            ?: throw IllegalStateException("USB アダプターが見つかりません。OTG ケーブルとアダプターの接続を確認してください")
        val name = UsbSerialTransport.describe(driver.device)
        log.log(CommLogger.Direction.INFO, "USB 機器: $name (${driver.javaClass.simpleName})")

        _state.value = ConnectionState.Connecting("USB の使用許可を待っています…")
        if (!UsbSerialTransport.requestPermission(context, driver.device)) {
            throw IllegalStateException("USB の使用が許可されませんでした")
        }

        return when (settings.usbAdapter) {
            UsbAdapter.OBDLINK -> {
                val t = UsbSerialTransport(context, driver, settings.elmBaudRate, dtr = true, rts = true)
                t.open()
                Elm327Protocol(
                    t,
                    Elm327Protocol.Options(
                        protocol = settings.elmProtocol,
                        singleResponseHint = settings.elmSingleResponseHint,
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
}
