package com.obdmeter.session

import android.content.Context
import com.obdmeter.core.BmwFastProtocol
import com.obdmeter.core.CommLogger
import com.obdmeter.core.Elm327Protocol
import com.obdmeter.core.Hex
import com.obdmeter.core.Transport
import com.obdmeter.data.AppSettings
import com.obdmeter.data.ConnectionType
import com.obdmeter.data.UsbAdapter
import com.obdmeter.usb.UsbSerialTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 接続テスト。うまくつながらないときに、アダプター・ケーブル・車両のどこで止まっているかを調べる。
 * 結果は 1 行ずつ [out] に渡す (画面表示と共有用)。生の送受信データも含める。
 */
class ConnectionTest(private val context: Context, private val log: CommLogger) {

    suspend fun run(settings: AppSettings, out: (String) -> Unit) = withContext(Dispatchers.IO) {
        fun line(s: String) { out(s); log.log(CommLogger.Direction.INFO, "[テスト] $s") }

        line("== 接続テスト ==")
        line("設定: ${settings.connectionType.name} / ${settings.usbAdapter.name} / ELM ${settings.elmBaudRate}bps ATSP${settings.elmProtocol} / DCAN 0x%02X エコー=${settings.dcanEcho}".format(settings.dcanTargetAddress))
        if (settings.connectionType == ConnectionType.SIMULATOR) {
            line("接続方式がシミュレーターです。設定で「USB 接続」にしてから実行してください。")
            return@withContext
        }

        val drivers = UsbSerialTransport.findDrivers(context)
        if (drivers.isEmpty()) {
            line("✗ USB シリアル機器が見つかりません。OTG ケーブル・アダプターの接続を確認してください。")
            return@withContext
        }
        drivers.forEach { line("USB 機器: ${UsbSerialTransport.describe(it.device)} (${it.javaClass.simpleName}, VID=%04X PID=%04X)".format(it.device.vendorId, it.device.productId)) }
        val driver = drivers.first()
        if (!UsbSerialTransport.requestPermission(context, driver.device)) {
            line("✗ USB の使用が許可されませんでした")
            return@withContext
        }

        when (settings.usbAdapter) {
            UsbAdapter.DCAN -> testDcan(driver, settings, ::line)
            UsbAdapter.OBDLINK -> testElm(driver, settings, ::line)
        }
        line("== テスト終了 ==")
    }

    // ---------------------------------------------------------------------
    // K+DCAN
    // ---------------------------------------------------------------------

    private suspend fun testDcan(
        driver: com.hoho.android.usbserial.driver.UsbSerialDriver,
        settings: AppSettings,
        line: (String) -> Unit,
    ) {
        val t = UsbSerialTransport(context, driver, 115200, dtr = true, rts = false)
        try {
            t.open()
        } catch (e: Exception) {
            line("✗ ポートを開けません: ${e.message}")
            return
        }
        try {
            val target = settings.dcanTargetAddress
            val probe = BmwFastProtocol.buildTelegram(target, 0xF1, intArrayOf(0x01, 0x00))
            line("--- 1. 信号線 (DTR/RTS) の組み合わせ ---")
            line("送信 (DME に 01 00): ${Hex.of(probe)}")
            var best: Pair<Boolean, Boolean>? = null
            var bestScore = -1
            for ((dtr, rts) in listOf(true to false, false to false, true to true, false to true)) {
                t.setLines(dtr, rts)
                delay(150)
                t.purge()
                t.write(probe)
                val rx = readFor(t, 800)
                val echo = rx.size >= probe.size && rx.copyOfRange(0, probe.size).contentEquals(probe)
                val rest = if (echo) rx.copyOfRange(probe.size, rx.size) else rx
                val score = when {
                    echo && rest.isNotEmpty() -> 2
                    echo || rx.isNotEmpty() -> 1
                    else -> 0
                }
                val verdict = when (score) {
                    2 -> "○ エコー + ECU 応答あり"
                    1 -> if (echo) "△ エコーのみ (ECU 応答なし)" else "△ 何か受信 (エコーではない)"
                    else -> "✗ 受信なし"
                }
                line("DTR=${on(dtr)} RTS=${on(rts)}: $verdict  受信${rx.size}バイト ${Hex.of(rx.take(48).toByteArray())}")
                if (score > bestScore) { bestScore = score; best = dtr to rts }
            }
            val (dtr, rts) = best ?: (true to false)
            t.setLines(dtr, rts)
            delay(150)

            line("--- 2. いろいろな問い合わせ (DTR=${on(dtr)} RTS=${on(rts)}) ---")
            val requests = listOf(
                "01 00 (OBD 対応 PID)" to intArrayOf(0x01, 0x00),
                "01 0C (OBD 回転数)" to intArrayOf(0x01, 0x0C),
                "1A 80 (ECU 識別 KWP)" to intArrayOf(0x1A, 0x80),
                "22 F1 90 (VIN UDS)" to intArrayOf(0x22, 0xF1, 0x90),
                "3E 00 (テスター接続)" to intArrayOf(0x3E, 0x00),
            )
            for ((name, data) in requests) {
                val tel = BmwFastProtocol.buildTelegram(target, 0xF1, data)
                t.purge()
                t.write(tel)
                val rx = readFor(t, 1500)
                val body = if (rx.size >= tel.size && rx.copyOfRange(0, tel.size).contentEquals(tel)) rx.copyOfRange(tel.size, rx.size) else rx
                line("$name → 送信 ${Hex.of(tel)}")
                line("    受信 ${Hex.of(body.take(64).toByteArray()).ifEmpty { "(なし)" }}")
            }

            line("--- 判定 ---")
            when (bestScore) {
                2 -> line("ケーブルと車両 (ECU 0x%02X) は通信できています。上の応答を共有してください。".format(target))
                1 -> {
                    line("ケーブルは反応していますが、車両の ECU が応答していません。")
                    line("・イグニッション ON (エンジン始動) で再実行してください")
                    line("・ケーブルのスイッチ: 2007/03 以降の E90 は D-CAN 側 (ピン 7-8 非短絡) にしてください")
                }
                else -> {
                    line("ケーブルから何も返ってきません。")
                    line("・ケーブルが OBD ポートに刺さっているか (ケーブルは車両の電源で動きます)")
                    line("・INPA 用に改造された K+DCAN ケーブルか (エコーの無いケーブルは設定でエコー除去を OFF に)")
                }
            }
        } finally {
            t.close()
        }
    }

    // ---------------------------------------------------------------------
    // OBDLink / ELM327
    // ---------------------------------------------------------------------

    private suspend fun testElm(
        driver: com.hoho.android.usbserial.driver.UsbSerialDriver,
        settings: AppSettings,
        line: (String) -> Unit,
    ) {
        val t = UsbSerialTransport(context, driver, settings.elmBaudRate, dtr = true, rts = true)
        try {
            t.open()
        } catch (e: Exception) {
            line("✗ ポートを開けません: ${e.message}")
            return
        }
        try {
            val elm = Elm327Protocol(t, Elm327Protocol.Options(protocol = settings.elmProtocol, commandTimeoutMs = 3000), log)
            fun cmd(c: String, timeout: Int = 3000): String {
                val r = elm.command(c, timeout).joinToString(" | ").ifEmpty { "(応答なし)" }
                line("$c → $r")
                return r
            }
            line("--- 1. アダプター ---")
            val adapter = runCatching { elm.initAdapter() }
            if (adapter.isFailure) {
                line("✗ ${adapter.exceptionOrNull()?.message}")
                line("ボーレートを変えて再実行してください (OBDLink SX は通常 115200)")
                return
            }
            line("○ ${adapter.getOrNull()}")
            cmd("AT@1")
            cmd("STI")
            cmd("ATRV")
            cmd("ATH1")   // 応答した ECU の ID も見たいのでヘッダー表示を ON

            line("--- 2. 標準 OBD (エンジン ECU) ---")
            for (proto in listOf(settings.elmProtocol, "6", "0").distinct()) {
                cmd("ATSP$proto")
                cmd("0100", if (proto == "0") 15000 else 5000)
                cmd("ATDPN")
            }

            line("--- 3. BMW 方式 (6F1 + 拡張アドレス、BimmerLink などが使う方式) ---")
            cmd("ATSP6")
            cmd("ATSH6F1")
            cmd("ATCEA12")
            cmd("ATCRA612")
            cmd("1A80", 5000)
            cmd("22F190", 5000)
            cmd("2101", 5000)
            // 元に戻す
            cmd("ATCRA")
            cmd("ATCEA")
            cmd("ATSH7DF")
            cmd("ATH0")
            cmd("ATSP${settings.elmProtocol}")

            line("--- 見かた ---")
            line("・2 で 41 00 … が返れば、標準の OBD で読めます (イグニッション ON が必要)")
            line("・3 で 5A 80 / 62 F1 90 / 61 01 … が返れば、イグニッション OFF でも BMW 方式で ECU と通信できます")
        } finally {
            t.close()
        }
    }

    private fun on(b: Boolean) = if (b) "ON" else "OFF"

    /** [ms] の間に届いたバイトをすべて集める */
    private fun readFor(t: Transport, ms: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + ms
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) break
            val n = t.read(buf, minOf(remaining, 50))
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
