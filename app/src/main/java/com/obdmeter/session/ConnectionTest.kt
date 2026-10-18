package com.obdmeter.session

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import com.obdmeter.core.ValueSearch
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

    /**
     * DME の BMW 独自の値 (22 xxxx) を順に読み、[targets] の値が入っている応答を探す。
     * (BimmerLink に出ている失火カウンタなどが、どの ID のどの位置にあるかを調べる)
     */
    suspend fun searchBmw(
        settings: AppSettings,
        targets: List<Long>,
        ranges: List<Pair<Int, IntRange>>,
        out: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        fun line(s: String) { out(s); log.log(CommLogger.Direction.INFO, "[探索] $s") }
        line("== BMW の値の探索 ==")
        line("探す値: ${targets.joinToString(", ")} (順番は問いません)")
        if (settings.usbAdapter != UsbAdapter.OBDLINK || settings.connectionType == ConnectionType.SIMULATOR) {
            line("✗ OBDLink (USB) で接続する設定のときだけ使えます")
            return@withContext
        }
        val driver = UsbSerialTransport.findDrivers(context).firstOrNull()
        if (driver == null) { line("✗ USB シリアル機器が見つかりません"); return@withContext }
        if (!UsbSerialTransport.requestPermission(context, driver.device)) { line("✗ USB の使用が許可されませんでした"); return@withContext }
        val t = UsbSerialTransport(context, driver, settings.elmBaudRate, dtr = true, rts = true)
        try {
            t.open()
            val elm = Elm327Protocol(t, Elm327Protocol.Options(protocol = settings.elmProtocol, commandTimeoutMs = 3000), log)
            line("○ ${elm.initAdapter()}")
            for (c in listOf("ATH1", "ATSP6", "ATSH6F1", "ATCEA12", "ATCRA612", "ATFCSH6F1", "ATFCSD12300000", "ATFCSM1", "ATST10")) {
                elm.command(c, 3000)
            }
            val total = ranges.sumOf { it.second.count() }
            var done = 0
            var positive = 0
            var hits = 0
            for ((svc, range) in ranges) {
                val idHex = if (svc == 0x22) "%04X" else "%02X"
                // 30 は「30 xx 01」(現在の値を読む) の形で問い合わせる
                line("--- %02X ${idHex}〜${idHex} ---".format(svc, range.first, range.last))
                for (id in range) {
                    currentCoroutineContext().ensureActive()
                    val req = "%02X$idHex".format(svc, id) + if (svc == 0x30) "01" else ""
                    val lines = elm.command(req, 1500)
                    done++
                    val msg = ValueSearch.assemble(lines)
                    val skip = if (svc == 0x21) 2 else 3
                    if (msg != null && msg.size >= skip && msg[0] == svc + 0x40) {
                        positive++
                        val data = msg.copyOfRange(skip, msg.size)
                        val m = ValueSearch.match(data, targets)
                        val hex = data.joinToString("") { "%02X".format(it) }
                        if (m != null) {
                            hits++
                            line("★ $req: $m")
                            line("   $hex")
                        } else {
                            line("$req (${data.size}バイト): ${hex.take(48)}${if (hex.length > 48) "…" else ""}")
                        }
                    }
                    if (done % 256 == 0) line("… $done / $total 件")
                }
            }
            line("応答のあった ID: $positive 件 / 一致の候補: $hits 件")
            if (hits == 0) line("見つかりませんでした。値が変わっていないか (エンジン停止中に実行) 確認してください")
            for (c in listOf("ATST32", "ATFCSM0", "ATCRA", "ATCEA", "ATSH7DF", "ATH0")) elm.command(c, 3000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            line("中止しました")
            throw e
        } catch (e: Exception) {
            line("✗ エラー: ${e.message}")
        } finally {
            t.close()
        }
        line("== 探索終了 ==")
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

            line("--- 3. BMW 方式 (6F1 + 拡張アドレス、BimmerLink などが使う方式) ---")
            cmd("ATSP6")
            cmd("ATSH6F1")
            cmd("ATCEA12")
            cmd("ATCRA612")
            // 複数フレームの応答を受け取るためのフロー制御 (拡張アドレス 12 付き)
            cmd("ATFCSH6F1")
            cmd("ATFCSD12300000")
            cmd("ATFCSM1")
            cmd("1A80", 5000)
            cmd("22F190", 5000)
            cmd("2101", 5000)

            line("--- 3b. 生涯失火数 (MSD80: 4850=1番 4851=5番 4852=3番 4853=6番 4854=2番 4855=4番) ---")
            // オイルセンサー (STATUS_OEL: 70 0E 01 + 油温 2 / レベル 1 / 品質 2)
            cmd("300E01", 2500)
            // 方法 2: 測定値ブロック (2C F0 で登録して 21 F0 で読む)。
            // 登録の要求は 1 フレーム (6 バイト) に収まらないので、自動整形を止めて手でフレームに分けて送る
            val ddli = buildList {
                addAll(listOf(0x2C, 0xF0))
                var pos = 1
                // 1 件ごとに「02 位置 大きさ ID(2) 1」(MSD80.prg の要求と同じ形)
                for (id in 0x4850..0x4855) { addAll(listOf(0x02, pos, 0x02, id shr 8, id and 0xFF, 0x01)); pos += 2 }
                // オイルレベル 長期平均 (1 バイト)
                addAll(listOf(0x02, pos, 0x01, 0x44, 0x00, 0x01))
            }
            fun hx(b: List<Int>) = b.joinToString("") { "%02X".format(it) }
            fun pad(b: List<Int>) = b + List(8 - b.size) { 0 }
            cmd("ATCAF0"); cmd("ATCEA"); cmd("ATST19")
            cmd(hx(pad(listOf(0x12, 0x03, 0x2C, 0xF0, 0x04))), 2500)               // 登録を消す
            cmd(hx(listOf(0x12, 0x10, ddli.size) + ddli.subList(0, 5)), 2500)       // 先頭フレーム (フロー制御 30 が返るはず)
            var idx = 5
            var sn = 1
            while (idx < ddli.size) {
                val part = ddli.subList(idx, minOf(ddli.size, idx + 6))
                cmd(hx(pad(listOf(0x12, 0x20 or (sn and 0x0F)) + part)), 2500)     // 続きのフレーム (最後に 6C F0 が返るはず)
                idx += 6; sn++
            }
            cmd("ATCAF1"); cmd("ATCEA12"); cmd("ATST32")
            cmd("ATFCSH6F1"); cmd("ATFCSD12300000"); cmd("ATFCSM1")
            cmd("21F0", 2500)   // 61 F0 + 2 バイト×6 (1, 5, 3, 6, 2, 4 番の順) + オイルレベル長期平均 1 バイト
            // STN の長いメッセージ送信 (STPX) でも試す
            cmd("STPX H:6F1, D:12" + hx(ddli), 3000)
            cmd("21F0", 2500)

            line("--- 4. BMW 独自の値 (DME 22 40xx。失火・燃焼状態の統計など) ---")
            // MSD80/81 の診断データ (例: 4003 = エンジンのラフネス、4031 = 気筒ごとの統計)
            // 可能性の高いもの (気筒ごとの統計・燃焼状態の確認値など) から先に読む
            val first = listOf(0x4031, 0x4030, 0x402D, 0x4032, 0x4026, 0x4027, 0x4028, 0x401A, 0x401B, 0x4001, 0x4002)
            for (id in first + (0x4000..0x403F).filter { it !in first && it !in 0x4000..0x4005 }) {
                cmd("22%04X".format(id), 2500)
            }
            // BMW 方式の設定を解除してから標準 OBD を確認する
            cmd("ATCRA")
            cmd("ATCEA")
            cmd("ATFCSM0")
            cmd("ATSH7DF")
            line("--- 2. 標準 OBD (エンジン ECU) ---")
            for (proto in listOf(settings.elmProtocol, "6", "0").distinct()) {
                cmd("ATSP$proto")
                cmd("0100", if (proto == "0") 15000 else 5000)
                cmd("ATDPN")
            }

            line("--- 2b. 失火カウンタ (OBD Mode 06。イグニッション ON、できればエンジン始動中) ---")
            cmd("ATSP6")
            cmd("ATSH7DF")
            // 06 00/20/…/A0 = 対応している MID の一覧。A1 = 全体、A2〜A7 = 1〜6 番の気筒
            for (mid in listOf("00", "20", "40", "60", "80", "A0", "C0", "E0")) cmd("06$mid", 5000)
            // A1 = 全体、A2〜A7 = 1〜6 番の気筒 (エンジン ECU 7E0 に直接聞く)
            cmd("ATSH7E0")
            for (mid in listOf("A1", "A2", "A3", "A4", "A5", "A6", "A7")) cmd("06$mid", 5000)
            // アプリの通常の読み方 (ヘッダー表示 OFF) での応答の形
            cmd("ATH0")
            cmd("06A2", 5000)
            cmd("ATH1")
            cmd("ATSH7DF")

            // 元に戻す
            cmd("ATCRA")
            cmd("ATCEA")
            cmd("ATFCSM0")
            cmd("ATSH7DF")
            cmd("ATH0")
            cmd("ATSP${settings.elmProtocol}")

            line("--- 見かた ---")
            line("・2 で 41 00 … が返れば、標準の OBD で読めます (イグニッション ON が必要)")
            line("・3 で 5A 80 / 62 F1 90 / 61 01 … が返れば、イグニッション OFF でも BMW 方式で ECU と通信できます")
            line("・2b で 46 A0 … の応答に A2〜A7 が含まれていれば、気筒ごとの失火カウンタに対応しています")
            line("・4 で 62 40 xx … が返った ID は、BMW 方式のカスタム PID (ヘッダー 6F1、拡張アドレス 12、モード 22) で読めます")
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
