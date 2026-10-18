package com.obdmeter.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 送信内容に応じて応答をキューに積む、テスト用の疑似アダプター。 */
abstract class FakeTransport : Transport {
    protected val rx = ArrayDeque<Byte>()
    val sent = mutableListOf<ByteArray>()
    override fun open() {}
    override fun close() {}
    override fun purge() { rx.clear() }
    override fun write(data: ByteArray) { sent += data; respond(data) }
    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buffer.size && rx.isNotEmpty()) buffer[n++] = rx.removeFirst()
        return n
    }
    protected fun push(bytes: ByteArray) { bytes.forEach { rx.addLast(it) } }
    abstract fun respond(data: ByteArray)
}

/** OBDLink 風 ELM327。エコー OFF 前は送信文字列をエコーする。 */
class FakeElm(private val replies: Map<String, String>) : FakeTransport() {
    private var echo = true
    override fun respond(data: ByteArray) {
        val cmd = String(data, Charsets.US_ASCII).trim()
        if (echo) push("$cmd\r".toByteArray())
        if (cmd == "ATE0") echo = false
        val reply = replies[cmd] ?: when {
            cmd.startsWith("AT") -> "OK"
            cmd.isEmpty() -> ""
            else -> "NO DATA"
        }
        push("$reply\r\r>".toByteArray())
    }
}

/** K+DCAN ケーブル + DME (0x12) 風。エコーしてから応答テレグラムを返す。 */
class FakeDcan(private val replies: Map<String, IntArray>, private val pending78: Boolean = false) : FakeTransport() {
    override fun respond(data: ByteArray) {
        push(data) // ケーブルのエコー
        val t = BmwFastProtocol.parseTelegram(data) ?: return
        val key = Hex.of(t.data)
        val reply = replies[key] ?: intArrayOf(0x7F, t.data[0], 0x12)
        if (pending78) push(BmwFastProtocol.buildTelegram(0xF1, t.target, intArrayOf(0x7F, t.data[0], 0x78)))
        // 別 ECU (0x18 = EGS) のノイズも混ぜる
        push(BmwFastProtocol.buildTelegram(0xF1, 0x18, intArrayOf(0x41, 0x00)))
        push(BmwFastProtocol.buildTelegram(0xF1, t.target, reply))
    }
}

/** 何も返さないアダプター (ボーレート違い・未接続) */
class SilentTransport : FakeTransport() {
    override fun respond(data: ByteArray) {}
}

/** K+DCAN ケーブルはエコーするが、車両 (DME) は眠っていて応答しない */
class EchoOnlyDcan : FakeTransport() {
    override fun respond(data: ByteArray) { push(data) }
}

class ProtocolTest {

    @Test
    fun elmConnectsToAdapterWhileVehicleSleeps() {
        var ignitionOn = false
        val fake = object : FakeTransport() {
            private var echo = true
            override fun respond(data: ByteArray) {
                val cmd = String(data, Charsets.US_ASCII).trim()
                if (echo) push("$cmd\r".toByteArray())
                if (cmd == "ATE0") echo = false
                val reply = when {
                    cmd == "0100" -> if (ignitionOn) "4100BE3EB811" else "CAN ERROR"
                    cmd == "ATRV" -> "12.4V"
                    cmd.startsWith("AT") -> "OK"
                    else -> ""
                }
                push("$reply\r\r>".toByteArray())
            }
        }
        val p = Elm327Protocol(fake, Elm327Protocol.Options(protocol = "6"))
        p.initAdapter()                       // イグニッション OFF でもアダプターは初期化できる
        assertNull(p.connectVehicle())
        assertTrue(p.lastFailure!!.contains("CAN ERROR"))
        assertEquals(12.4, p.readAdapterVoltage()!!, 0.001) // 待機中もバッテリー電圧は読める
        ignitionOn = true
        assertNotNull(p.connectVehicle())
    }

    @Test
    fun elmIgnitionOffEcuRefusalCountsAsConnected() {
        // 実車ログ (E90, イグニッション OFF): 0100 → 7E8 03 7F 01 22
        val fake = FakeElm(mapOf(
            "0100" to "7F0122",
            "0100 1" to "7F0122",
            "010C 1" to "7F0122",
            "ATDPN" to "6",
            "ATDP" to "ISO 15765-4 (CAN 11/500)",
        ))
        val p = Elm327Protocol(fake, Elm327Protocol.Options(protocol = "6"))
        p.initAdapter()
        val desc = p.connectVehicle()
        assertNotNull(desc)
        assertTrue(desc!!.contains("イグニッション OFF"))
        assertTrue(p.lastResponded)

        val reader = ObdReader(p)
        reader.detectSupported()
        assertNull(reader.supported)
        val r = reader.poll(listOf(Pids.get("RPM")!!), mutableMapOf())
        assertEquals(0, r.ok)
        assertEquals(1, r.refused)
        assertTrue(r.vehicleAlive)
        assertTrue(p.lastFailure!!.contains("22"))
    }

    @Test
    fun fallbackIsReadOnlyWhenPrimaryMissing() {
        var ignitionOn = false
        var customReads = 0
        val proto = object : ObdProtocol {
            override fun initAdapter() = ""
            override fun connectVehicle() = ""
            override var lastResponded = false
            override fun readPid(pid: Int): IntArray? {
                lastResponded = !ignitionOn
                return if (ignitionOn) intArrayOf(130) else null   // 水温 90℃ / OFF は 7F 01 22
            }
            override fun query(request: PidRequest): IntArray? { customReads++; lastResponded = false; return intArrayOf(128) }
            override fun close() {}
        }
        val coolant = Pids.get("COOLANT")!!
        val customCoolant = Pids.custom("CUSTOM_9", "水温 (DME)", "水温", "°C", "22", "4300", "", "",
            "A-40", 0.0, 150.0, 0, null, null, false)
        val reader = ObdReader(proto)
        val values = mutableMapOf<String, Double>()
        val fallbacks = mapOf("COOLANT" to customCoolant)

        // イグニッション OFF: 標準は拒否 → 代替 (カスタム) を読む。判定は「イグニッション OFF」
        var r = ObdReader.Result(0, 0)
        repeat(3) { r = reader.poll(listOf(coolant), values, fallbacks) }
        assertNull(values["COOLANT"])
        assertEquals(88.0, values["CUSTOM_9"]!!, 0.01)
        assertTrue(r.ignitionOff)
        assertTrue(r.vehicleAlive)

        // イグニッション ON: 標準が読める → 代替は読まず値も消える
        ignitionOn = true
        val before = customReads
        r = reader.poll(listOf(coolant), values, fallbacks) // 拒否された項目は毎回試すので、次の 1 回で戻る
        assertEquals(90.0, values["COOLANT"]!!, 0.01)
        assertNull(values["CUSTOM_9"])
        assertTrue(!r.ignitionOff)
        assertEquals(before, customReads)
    }

    @Test
    fun misfireCounterFromMode06MultiFrame() {
        // 46 A2 | 0B 24 00 0A 00 00 FF FF | 0C 24 00 03 00 00 FF FF  (18 バイト = 0x012)
        val fake = FakeElm(mapOf(
            "06A2" to "012\r0:46A20B24000A\r1:0000FFFF0C2400\r2:030000FFFF0000",
            "06A3" to "7F0612",
        ))
        val p = Elm327Protocol(fake)
        val cyl1 = Pids.get("MISFIRE_1")!!
        val cyl2 = Pids.get("MISFIRE_2")!!
        val values = mutableMapOf<String, Double>()
        ObdReader(p).poll(listOf(cyl1, cyl2), values)
        assertEquals(3.0, values["MISFIRE_1"]!!, 0.0)   // TID 0C (今回のサイクル) を使う
        assertNull(values["MISFIRE_2"])                  // 非対応 (7F 06 12)
        // CAN の形式: 2 件目の前に MID が付く (46 A2 0B ... A2 0C ...)
        val fake2 = FakeElm(mapOf("06A2" to "013\r0:46A20B24000A\r1:0000FFFFA20C24\r2:00050000FFFF00"))
        val v2 = mutableMapOf<String, Double>()
        ObdReader(Elm327Protocol(fake2)).poll(listOf(cyl1), v2)
        assertEquals(5.0, v2["MISFIRE_1"]!!, 0.0)
        // TID 0C が無ければ 0B (平均) を使う
        assertEquals(10.0, Pids.decodeMisfire(intArrayOf(0x0B, 0x24, 0x00, 0x0A, 0, 0, 0xFF, 0xFF)), 0.0)
    }

    @Test
    fun elmNoDataIsNotAlive() {
        val fake = FakeElm(mapOf("0100" to "4100BE3EB811", "010C 1" to "NO DATA"))
        val p = Elm327Protocol(fake)
        p.initAdapter()
        val r = ObdReader(p).poll(listOf(Pids.get("RPM")!!), mutableMapOf())
        assertTrue(!r.vehicleAlive)
    }

    @Test
    fun elmSilentAdapterFailsFast() {
        val p = Elm327Protocol(SilentTransport(), Elm327Protocol.Options(commandTimeoutMs = 50))
        try {
            p.initAdapter()
            throw AssertionError("should fail")
        } catch (e: ProtocolException) { /* ok */ }
    }

    @Test
    fun dcanEchoButNoEcuMeansVehicleAsleep() {
        val p = BmwFastProtocol(EchoOnlyDcan(), BmwFastProtocol.Options(responseTimeoutMs = 100))
        p.initAdapter()
        assertNull(p.connectVehicle())
        assertTrue(!p.echoMissing)
    }

    @Test
    fun dcanNoEchoIsAdapterError() {
        val p = BmwFastProtocol(SilentTransport(), BmwFastProtocol.Options(responseTimeoutMs = 100))
        try {
            p.connectVehicle()
            throw AssertionError("should fail")
        } catch (e: ProtocolException) { /* ok */ }
    }

    @Test
    fun elmParseMode01() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8), Elm327Protocol.parseMode01(listOf("410C1AF8"), 0x0C))
        assertArrayEquals(intArrayOf(0x1A, 0xF8), Elm327Protocol.parseMode01(listOf("SEARCHING...", "41 0C 1A F8"), 0x0C))
        assertNull(Elm327Protocol.parseMode01(listOf("NO DATA"), 0x0C))
        assertNull(Elm327Protocol.parseMode01(listOf("410D32"), 0x0C))
    }

    @Test
    fun elmInitializeAndRead() {
        val fake = FakeElm(mapOf(
            "ATWS" to "ELM327 v1.4b",
            "ATI" to "ELM327 v1.4b",
            "0100" to "SEARCHING...\r4100BE3EB811",
            "ATDPN" to "A6",
            "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)",
            "010C 1" to "410C1AF8",
            "010D 1" to "410D32",
            "0100 1" to "4100BE3EB811",
            "0120 1" to "NO DATA",
            "ATRV" to "12.6V",
        ))
        val p = Elm327Protocol(fake)
        val desc = p.initialize()
        assertTrue(desc.contains("ISO 15765-4"))
        assertArrayEquals(intArrayOf(0x1A, 0xF8), p.readPid(0x0C))
        assertEquals(12.6, p.readAdapterVoltage()!!, 0.001)

        val reader = ObdReader(p)
        reader.detectSupported()
        assertTrue(reader.supported!!.contains(0x0C))
        val values = mutableMapOf<String, Double>()
        reader.poll(listOf(Pids.get("RPM")!!, Pids.get("SPEED")!!), values)
        assertEquals(1726.0, values["RPM"]!!, 0.01) // 0x1AF8 / 4
        assertEquals(50.0, values["SPEED"]!!, 0.01)
    }

    @Test
    fun elmFallsBackWhenHintUnsupported() {
        val fake = FakeElm(mapOf("010C 1" to "?", "010C" to "410C0FA0", "0100" to "4100BE3EB811"))
        val p = Elm327Protocol(fake)
        assertArrayEquals(intArrayOf(0x0F, 0xA0), p.readPid(0x0C))
        // 2 回目からはヒントなしで送る
        p.readPid(0x0C)
        assertEquals("010C\r", String(fake.sent.last()))
    }

    @Test
    fun bmwFastTelegramFormat() {
        val tel = BmwFastProtocol.buildTelegram(0x12, 0xF1, intArrayOf(0x01, 0x0C))
        // 82 12 F1 01 0C + checksum(0x82+0x12+0xF1+0x01+0x0C = 0x192 -> 0x92)
        assertEquals("82 12 F1 01 0C 92", Hex.of(tel))
        val parsed = BmwFastProtocol.parseTelegram(tel)
        assertNotNull(parsed)
        assertEquals(0x12, parsed!!.target)
        assertEquals(0xF1, parsed.source)
        assertArrayEquals(intArrayOf(0x01, 0x0C), parsed.data)

        // 長いデータは長さバイト付き形式
        val long = BmwFastProtocol.buildTelegram(0x12, 0xF1, IntArray(70) { it })
        assertEquals(0x80, long[0].toInt() and 0xFF)
        assertEquals(70, long[3].toInt() and 0xFF)
        assertArrayEquals(IntArray(70) { it }, BmwFastProtocol.parseTelegram(long)!!.data)

        // チェックサム不一致は null
        val bad = tel.copyOf().also { it[5] = 0 }
        assertNull(BmwFastProtocol.parseTelegram(bad))
    }

    @Test
    fun bmwFastReadThroughCable() {
        val fake = FakeDcan(mapOf(
            "01 00" to intArrayOf(0x41, 0x00, 0xBE, 0x3E, 0xB8, 0x11),
            "01 0C" to intArrayOf(0x41, 0x0C, 0x1A, 0xF8),
            "01 05" to intArrayOf(0x41, 0x05, 0x7B),
        ))
        val p = BmwFastProtocol(fake)
        assertTrue(p.initialize().contains("0x12"))
        assertArrayEquals(intArrayOf(0x1A, 0xF8), p.readPid(0x0C))
        assertArrayEquals(intArrayOf(0x7B), p.readPid(0x05))
        // 否定応答 (7F 01 12) は null
        assertNull(p.readPid(0x5C))
    }

    @Test
    fun bmwFastWaitsOnResponsePending() {
        val fake = FakeDcan(mapOf("01 0D" to intArrayOf(0x41, 0x0D, 0x64)), pending78 = true)
        assertArrayEquals(intArrayOf(0x64), BmwFastProtocol(fake).readPid(0x0D))
    }

    @Test
    fun simulatorDecodesThroughSamePath() {
        val sim = SimulatorProtocol(responseDelayMs = 0)
        val reader = ObdReader(sim)
        reader.detectSupported()
        val s = reader.supported!!
        assertTrue(s.contains(0x0C) && s.contains(0x42) && s.contains(0x49))
        assertTrue(!s.contains(0x5C))
        val values = mutableMapOf<String, Double>()
        reader.poll(Pids.ALL, values)
        assertTrue(values["RPM"]!! in 600.0..7300.0)
        assertTrue(values["ECU_VOLT"]!! in 13.0..15.0)
        assertNotNull(values["BOOST"])
        assertNull(values["OIL_TEMP"])
        assertEquals(14.2, values["BATTERY"]!!, 0.01)
    }

    @Test
    fun elmParsesHeaderLines() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8), Elm327Protocol.parseMode01(listOf("7E8 04 41 0C 1A F8"), 0x0C))
        assertArrayEquals(intArrayOf(0x32), Elm327Protocol.parseMode01(listOf("18DAF110 03 41 0D 32"), 0x0D))
    }

    @Test
    fun supportedPidsMergeAllEcus() {
        // EGS (先に返す) は回転数だけ、DME は車速・水温も対応、という状況
        val fake = FakeElm(mapOf(
            "0100" to "4100 00100000\r4100 081C0000", // EGS: 0C / DME: 05,0C,0D,0E
            "0100 1" to "410000100000",
        ))
        val s = Elm327Protocol(fake).readSupportedPids()
        assertTrue(s.containsAll(listOf(0x05, 0x0C, 0x0D)))
    }

    @Test
    fun readerHoldsValueOnTransientFailure() {
        var fail = false
        val proto = object : ObdProtocol {
            override fun initAdapter() = ""
            override fun connectVehicle() = ""
            override fun readPid(pid: Int): IntArray? = if (fail) null else intArrayOf(0x0F, 0xA0)
            override fun close() {}
        }
        val reader = ObdReader(proto)
        val values = mutableMapOf<String, Double>()
        val rpm = listOf(Pids.get("RPM")!!)
        reader.poll(rpm, values)
        assertEquals(1000.0, values["RPM"]!!, 0.01)
        fail = true
        reader.poll(rpm, values); reader.poll(rpm, values)
        assertEquals(1000.0, values["RPM"]!!, 0.01) // 2 回失敗までは保持
        reader.poll(rpm, values)
        assertNull(values["RPM"]) // 3 回連続で消える
    }

    @Test
    fun readerRetriesPidMarkedUnsupported() {
        val proto = object : ObdProtocol {
            override fun initAdapter() = ""
            override fun connectVehicle() = ""
            override fun readPid(pid: Int): IntArray? = when (pid) {
                0x00 -> intArrayOf(0x00, 0x10, 0x00, 0x00) // 0C だけ対応と申告
                0x0D -> intArrayOf(80)                      // でも実は車速も読める
                else -> intArrayOf(0x0F, 0xA0)
            }
            override fun close() {}
        }
        val reader = ObdReader(proto)
        reader.detectSupported()
        assertTrue(!reader.supported!!.contains(0x0D))
        val values = mutableMapOf<String, Double>()
        reader.poll(listOf(Pids.get("SPEED")!!), values)
        assertEquals(80.0, values["SPEED"]!!, 0.01)
        assertTrue(reader.supported!!.contains(0x0D))
    }

    @Test
    fun voltageParsing() {
        assertEquals(14.1, Elm327Protocol.parseVoltage(listOf("14.1V"))!!, 0.001)
        assertEquals(12.6, Elm327Protocol.parseVoltage(listOf("12.6 V"))!!, 0.001)
        assertNull(Elm327Protocol.parseVoltage(listOf("0.0V")))
        assertNull(Elm327Protocol.parseVoltage(listOf("410C0B40")))
    }

    // ---- カスタム PID ----

    @Test
    fun expressionBasics() {
        val d = intArrayOf(0x1A, 0xF8, 0x80, 0xFF)
        assertEquals(1726.0, Expression.compile("(A*256+B)/4").evaluate(d), 1e-9)
        assertEquals(-14.0, Expression.compile("a - 40").evaluate(d), 1e-9) // 小文字も可
        assertEquals(-128.0, Expression.compile("signed(C)").evaluate(d), 1e-9)
        assertEquals(-1.0, Expression.compile("signed16(C*256+D) / 0x7F80 * 0 + signed(D)").evaluate(d), 1e-9)
        assertEquals(1.0, Expression.compile("bit(B, 3)").evaluate(d), 1e-9)
        assertEquals(26.0, Expression.compile("max(A, 3, -2)").evaluate(d), 1e-9)
        assertEquals(-26.0, Expression.compile("-A").evaluate(d), 1e-9)
        assertEquals(4, Expression.compile("D").requiredBytes)
    }

    @Test
    fun expressionErrors() {
        for (bad in listOf("", "A+", "(A", "AB", "foo(A)", "A $ B", "bit(A)")) {
            try {
                Expression.compile(bad)
                throw AssertionError("should fail: $bad")
            } catch (e: ExpressionException) { /* ok */ }
        }
        try {
            Expression.compile("A*B").evaluate(intArrayOf(1))
            throw AssertionError("should fail: short data")
        } catch (e: ExpressionException) { /* ok */ }
    }

    @Test
    fun pidRequestParse() {
        val r = PidRequest.parse("22", "4205", "7e0", "")
        assertEquals(0x22, r.mode)
        assertArrayEquals(intArrayOf(0x42, 0x05), r.pid)
        assertEquals("7E0", r.header)
        assertTrue(PidRequest.parse("01", "0C").isPlainMode01)
        for ((m, p) in listOf("ZZ" to "0C", "22" to "123", "22" to "XYZW")) {
            try { PidRequest.parse(m, p); throw AssertionError("should fail $m $p") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test
    fun elmMultiFrameResponse() {
        // ISO-TP 複数フレーム (10 バイト): 62 F1 90 + 7 バイト、最後のフレームは埋め草付き
        val lines = listOf("00A", "0:62F19057424130", "1:31323334AAAAAA")
        val data = Elm327Protocol.parseResponseAll(lines, 0x22, intArrayOf(0xF1, 0x90)).single()
        assertArrayEquals(intArrayOf(0x57, 0x42, 0x41, 0x30, 0x31, 0x32, 0x33), data)
        // 単一フレーム
        assertArrayEquals(intArrayOf(0x00, 0x7B),
            Elm327Protocol.parseResponseAll(listOf("62420500 7B"), 0x22, intArrayOf(0x42, 0x05)).single())
        assertTrue(Elm327Protocol.negativeResponse(listOf("7F2231"), 0x22)!!.contains("31"))
    }

    @Test
    fun elmCustomQuerySwitchesHeaderAndRestores() {
        val fake = FakeElm(mapOf(
            "0100" to "4100BE3EB811",
            "ATDPN" to "6",
            "224205" to "6242050C80",
            "010C 1" to "410C0FA0",
        ))
        val p = Elm327Protocol(fake, Elm327Protocol.Options(protocol = "6"))
        p.initialize()
        val data = p.query(PidRequest.parse("22", "4205", "7E0"))
        assertArrayEquals(intArrayOf(0x0C, 0x80), data)
        p.readPid(0x0C)
        val sent = fake.sent.map { String(it).trim() }
        val i = sent.indexOf("ATSH7E0")
        assertTrue(i >= 0 && sent[i + 1] == "224205")
        // 標準 PID の前に既定ヘッダーへ戻す
        assertTrue(sent.indexOf("ATSH7DF") in (i + 2) until sent.indexOf("010C 1"))
    }

    @Test
    fun bmwFastCustomQueryToOtherEcu() {
        val fake = FakeDcan(mapOf("22 42 05" to intArrayOf(0x62, 0x42, 0x05, 0x0C, 0x80)))
        val p = BmwFastProtocol(fake)
        assertArrayEquals(intArrayOf(0x0C, 0x80), p.query(PidRequest.parse("22", "4205", "12")))
        assertNull(p.query(PidRequest.parse("22", "9999")))
        assertTrue(p.lastFailure!!.contains("7F"))
    }

    @Test
    fun readerReadsCustomPid() {
        val def = Pids.custom("CUSTOM_1", "油温 (DME)", "油温", "°C", "22", "4402", "", "",
            "A*0.75-48", 0.0, 150.0, 0, null, null, false)
        val reader = ObdReader(SimulatorProtocol(responseDelayMs = 0))
        val values = mutableMapOf<String, Double>()
        reader.poll(listOf(def), values)
        // シミュレーターは DID 4402 → 44 02 ... を返すので A = 0x44
        assertEquals(0x44 * 0.75 - 48, values["CUSTOM_1"]!!, 1e-9)
        val catalog = PidCatalog(listOf(def))
        assertEquals("油温", catalog.get("CUSTOM_1")!!.shortName)
        assertEquals("回転数", catalog.get("RPM")!!.shortName)
    }

    @Test
    fun pidFormulas() {
        assertEquals(90.0, Pids.get("COOLANT")!!.decode(intArrayOf(130)), 0.001)
        assertEquals(-3.125, Pids.get("STFT1")!!.decode(intArrayOf(124)), 0.001)
        assertEquals(14.2, Pids.get("ECU_VOLT")!!.decode(intArrayOf(0x37, 0x78)), 0.001)
        assertEquals(0.5, Pids.get("BOOST")!!.derive(mapOf("MAP" to 151.0, "BARO" to 101.0))!!, 0.001)
    }

    @Test
    fun bmwLifetimeMisfireViaDdli() {
        // 実車のログに合わせた応答 (BMW 方式・ヘッダー表示 ON)。登録は 2C F0 … を手で分割して送る
        val define = Pids.MISFIRE_LIFE_SETUP[1]
        val ff = "1210%02X".format(define.size) + define.take(5).joinToString("") { "%02X".format(it) }
        val cfs = mutableListOf<String>()
        var idx = 5; var sn = 1
        while (idx < define.size) {
            val part = define.toList().subList(idx, minOf(define.size, idx + 6))
            val frame = listOf(0x12, 0x20 or sn) + part
            cfs += (frame + List(8 - frame.size) { 0 }).joinToString("") { "%02X".format(it) }
            idx += 6; sn++
        }
        // 1, 5, 3, 6, 2, 4 番の順に 14, 71, 27, 37, 50, 27
        val replies = mutableMapOf(
            "2CF004" to "612F1026CF0FFFFFFFF",
            ff to "612F1300000FFFFFFFF",
            cfs.last() to "612F1026CF0FFFFFFFF",
            "21F0" to "612F1100F61F0000E00\r612F12147001B002500\r612F12232001B90FFFF",
        )
        val fake = FakeElm(replies)
        val p = Elm327Protocol(fake)
        p.initAdapter()
        val values = mutableMapOf<String, Double>()
        ObdReader(p).poll((1..6).map { Pids.get("MISFIRE_LIFE_$it")!! }, values)
        assertEquals(14.0, values["MISFIRE_LIFE_1"]!!, 0.0)
        assertEquals(50.0, values["MISFIRE_LIFE_2"]!!, 0.0)
        assertEquals(27.0, values["MISFIRE_LIFE_3"]!!, 0.0)
        assertEquals(27.0, values["MISFIRE_LIFE_4"]!!, 0.0)
        assertEquals(71.0, values["MISFIRE_LIFE_5"]!!, 0.0)
        assertEquals(37.0, values["MISFIRE_LIFE_6"]!!, 0.0)
        // 同じブロックのオイルレベル長期平均 (0x90 × 0.293 = 42.2 mm)
        val v2 = mutableMapOf<String, Double>()
        ObdReader(p).poll(listOf(Pids.get("OIL_LEVEL_LONG")!!), v2)
        assertEquals(0x90 * 0.29296875, v2["OIL_LEVEL_LONG"]!!, 1e-9)
        // 登録は 1 回だけ
        assertEquals(1, fake.sent.count { String(it).trim() == "2CF004" })
    }
}
