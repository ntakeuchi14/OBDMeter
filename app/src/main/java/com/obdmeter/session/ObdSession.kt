package com.obdmeter.session

import android.content.Context
import com.obdmeter.core.BmwFastProtocol
import com.obdmeter.core.CommLogger
import com.obdmeter.core.Elm327Protocol
import com.obdmeter.core.ObdProtocol
import com.obdmeter.core.ObdReader
import com.obdmeter.core.PidDef
import com.obdmeter.core.SimulatorProtocol
import com.obdmeter.data.AppSettings
import com.obdmeter.data.ConnectionType
import com.obdmeter.data.UsbAdapter
import com.obdmeter.usb.UsbSerialTransport
import kotlinx.coroutines.CancellationException
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

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val message: String) : ConnectionState
    data class Connected(val description: String) : ConnectionState
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

    private var job: Job? = null

    fun connect(settings: AppSettings) {
        val old = job
        disconnect()
        job = scope.launch(Dispatchers.IO) {
            // 前のセッションが USB を閉じ終わるまで待つ
            old?.join()
            var attempt = 0
            while (isActive) {
                attempt++
                val ok = runSession(settings)
                if (!isActive) break
                if (!settings.autoReconnect || !ok && attempt >= 50) break
                delay(3000)
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _state.value = ConnectionState.Disconnected
        _values.value = emptyMap()
        _rate.value = 0.0
    }

    /** 1 回分の接続〜切断。通信が成立していたら true。 */
    private suspend fun runSession(settings: AppSettings): Boolean {
        var protocol: ObdProtocol? = null
        var established = false
        try {
            _state.value = ConnectionState.Connecting("アダプターを探しています…")
            val p = createProtocol(settings)
            protocol = p
            _state.value = ConnectionState.Connecting("車両と通信を確立しています…")
            val desc = p.initialize()
            log.log(CommLogger.Direction.INFO, "接続: $desc")
            val reader = ObdReader(p, log)
            _state.value = ConnectionState.Connecting("対応項目を確認しています…")
            reader.detectSupported()
            _supported.value = reader.supported
            _state.value = ConnectionState.Connected(desc)
            established = true

            val values = HashMap<String, Double>()
            var deadCycles = 0
            var count = 0
            var windowStart = System.currentTimeMillis()
            while (currentCoroutineContext().isActive) {
                val defs = activePids
                if (defs.isEmpty()) { delay(100); continue }
                val r = reader.poll(defs, values)
                _values.value = HashMap(values)
                count += r.ok + r.failed
                deadCycles = if (r.ok == 0 && r.failed > 0) deadCycles + 1 else 0
                if (deadCycles >= 5) throw IllegalStateException("車両からの応答が途絶えました (イグニッション OFF?)")
                val now = System.currentTimeMillis()
                if (now - windowStart >= 1000) {
                    _rate.value = count * 1000.0 / (now - windowStart)
                    count = 0
                    windowStart = now
                }
                if (r.ok + r.failed == 0) delay(20) // 何も読まなかったサイクルで CPU を回し続けない
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            log.log(CommLogger.Direction.ERROR, msg)
            _state.value = ConnectionState.Error(msg, retrying = settings.autoReconnect)
            _values.value = emptyMap()
            _rate.value = 0.0
        } finally {
            withContext(NonCancellable) { runCatching { protocol?.close() } }
        }
        return established
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
