package com.obdmeter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通信をまねるプロトコル。BMW の測定値ブロック (2C lid … / 21 lid) と、要求ごとの固定応答を返す。
 * 送った要求を [queries] に記録する
 */
class FakeBmwProtocol(
    /** 測定値 ID → 応答のバイト */
    private val measurements: Map<Int, IntArray> = emptyMap(),
    /** 要求の表記 ("30 1901 @7E0" など) → 応答のデータ部 */
    private val replies: Map<String, IntArray> = emptyMap(),
    /** F0 以外の測定値ブロックを断る DME */
    private val onlyF0: Boolean = false,
) : ObdProtocol {
    val queries = mutableListOf<String>()
    private val defined = HashMap<Int, List<Pair<Int, Int>>>()
    private var responded = false
    override val lastResponded: Boolean get() = responded

    override fun initAdapter() = "fake"
    override fun connectVehicle() = "fake"
    override fun readPid(pid: Int): IntArray? = null
    override fun close() {}

    override fun query(request: PidRequest): IntArray? {
        queries += request.toString()
        responded = false
        if (request.mode == 0x21) {
            val lid = request.pid[0]
            if (onlyF0 && lid != 0xF0) { responded = true; return null }
            // 準備の要求 (登録) を読み解く: 2C lid [02 位置 バイト数 ID上 ID下 01]…
            val def = request.setup.last()
            val entries = (2 until def.size step 6).map { i -> ((def[i + 3] shl 8) or def[i + 4]) to def[i + 2] }
            if (entries.any { it.first !in measurements }) { responded = true; return null }
            defined[lid] = entries
            return entries.flatMap { (id, size) -> measurements.getValue(id).take(size) }.toIntArray()
        }
        return replies[request.toString()]
    }
}

class ObdReaderTest {
    private fun item(key: String) = Pids.get(key) ?: error("no item $key")

    private val coolant = item("BMW_COOLANT")      // 0x581F, 1 バイト
    private val rpm = item("BMW_RPM")              // 0x4807, 2 バイト
    private val battery = item("BMW_BATTERY")      // 0x460A, 2 バイト
    private val dme = mapOf(
        0x581F to intArrayOf(184),                 // 184 × 0.75 − 48 = 90 °C
        0x4807 to intArrayOf(0x0B, 0xB8),          // 3000 rpm
        0x460A to intArrayOf(0x03, 0x52),          // 850 × 0.015 = 12.75 V
        0x5A2C to intArrayOf(0x63, 0xFF),          // 25599 × 0.0390625 ≈ 999.96 hPa
        0x4201 to intArrayOf(0x2F, 0xA0),          // 12192 × 0.0829 ≈ 1010.9 hPa
    )

    @Test
    fun readsAllBmwItemsInOneBlockWithoutIntervals() {
        val p = FakeBmwProtocol(dme)
        val reader = ObdReader(p)
        val values = HashMap<String, Double>()
        reader.poll(listOf(coolant, rpm, battery), values)
        assertEquals(1, p.queries.size)
        assertTrue(p.queries[0].startsWith("21 F0"))
        assertEquals(90.0, values.getValue("BMW_COOLANT"), 1e-6)
        assertEquals(3000.0, values.getValue("BMW_RPM"), 1e-6)
        assertEquals(12.75, values.getValue("BMW_BATTERY"), 1e-6)
    }

    @Test
    fun splitsBmwItemsByIntervalAndReadsOnlyDueGroups() {
        var now = 1_000_000L
        val p = FakeBmwProtocol(dme)
        val reader = ObdReader(p, clock = { now })
        reader.intervals = mapOf("BMW_COOLANT" to 5_000L, "BMW_BATTERY" to 5_000L)
        val values = HashMap<String, Double>()
        reader.poll(listOf(coolant, rpm, battery), values)
        // 周期なし (回転数) → F0、5 秒 (水温・電圧) → F1
        assertEquals(listOf("21 F0", "21 F1"), p.queries.map { it.take(5) })
        p.queries.clear()
        now += 1_000
        reader.poll(listOf(coolant, rpm, battery), values)
        assertEquals("1 秒後は回転数のまとまりだけ", listOf("21 F0"), p.queries.map { it.take(5) })
        p.queries.clear()
        now += 5_000
        reader.poll(listOf(coolant, rpm, battery), values)
        assertEquals(listOf("21 F0", "21 F1"), p.queries.map { it.take(5) })
        assertEquals(90.0, values.getValue("BMW_COOLANT"), 1e-6)
    }

    @Test
    fun fallsBackToSingleBlockWhenDmeRefusesOtherNumbers() {
        val p = FakeBmwProtocol(dme, onlyF0 = true)
        val reader = ObdReader(p)
        reader.intervals = mapOf("BMW_COOLANT" to 5_000L)
        val values = HashMap<String, Double>()
        reader.poll(listOf(coolant, rpm), values)
        assertTrue(reader.multiBlockUnsupported)
        p.queries.clear()
        reader.poll(listOf(coolant, rpm), values)
        assertEquals("以後は F0 だけでまとめて読む", listOf("21 F0"), p.queries.map { it.take(5) })
        assertEquals(90.0, values.getValue("BMW_COOLANT"), 1e-6)
        assertEquals(3000.0, values.getValue("BMW_RPM"), 1e-6)
    }

    @Test
    fun sampleReadUsesSeparateNumber() {
        val p = FakeBmwProtocol(dme)
        val reader = ObdReader(p)
        val values = HashMap<String, Double>()
        reader.poll(listOf(coolant), values, forceAll = true)
        assertEquals(listOf("21 F9"), p.queries.map { it.take(5) })
        assertEquals(90.0, values.getValue("BMW_COOLANT"), 1e-6)
    }

    @Test
    fun derivedBoostReadsItsDependencies() {
        val p = FakeBmwProtocol(dme)
        val reader = ObdReader(p)
        val values = HashMap<String, Double>()
        reader.poll(listOf(item("BMW_BOOST_BAR"), item("BMW_BOOST_PSI")), values)
        val baro = 12192 * 0.08291752636432648
        val boost = (25599 * 0.0390625 - baro) / 1000
        assertEquals(boost, values.getValue("BMW_BOOST_BAR"), 1e-6)
        assertEquals(boost * 14.503774, values.getValue("BMW_BOOST_PSI"), 1e-6)
        assertEquals("依存先はまとめて 1 回", 1, p.queries.size)
    }

    @Test
    fun itemsOfSameStatusJobShareOneRequestPerCycle() {
        val pre = item("MSD80S_STATUS_SDF2_STAT_PVDKDS_WERT")
        val post = item("MSD80S_STATUS_SDF2_STAT_PS_IST_WERT")
        val temp = item("MSD80S_STATUS_SDF2_STAT_TANS_WERT")
        val p = FakeBmwProtocol(replies = mapOf("30 1901 @7E0" to intArrayOf(0x63, 0xFF, 0x60, 0x00, 0x01, 0x2C)))
        val reader = ObdReader(p)
        val values = HashMap<String, Double>()
        reader.poll(listOf(pre, post, temp), values)
        assertEquals(listOf("30 1901 @7E0"), p.queries)
        assertEquals(25599 * 0.0390625, values.getValue(pre.key), 1e-3)
        assertEquals(24576 * 0.0390625, values.getValue(post.key), 1e-3)
        assertEquals(30.0, values.getValue(temp.key), 1e-6)
    }

    @Test
    fun keepsValueWhileIntervalNotDue() {
        var now = 0L
        val flap = item("BMW_EXHAUST_FLAP")
        val p = FakeBmwProtocol(replies = mapOf("30 C101 @7E0" to intArrayOf(1)))
        val reader = ObdReader(p, clock = { now })
        reader.intervals = mapOf(flap.key to 2_000L)
        val values = HashMap<String, Double>()
        reader.poll(listOf(flap), values)
        now += 500
        reader.poll(listOf(flap), values)
        assertEquals(1, p.queries.size)
        assertEquals(1.0, values.getValue(flap.key), 0.0)
        now += 2_000
        reader.poll(listOf(flap), values)
        assertEquals(2, p.queries.size)
    }

    @Test
    fun unknownMeasurementIsDroppedAndOthersStillRead() {
        val p = FakeBmwProtocol(dme - 0x460A)
        val reader = ObdReader(p)
        val values = HashMap<String, Double>()
        reader.poll(listOf(coolant, battery), values)
        assertNull(values["BMW_BATTERY"])
        p.queries.clear()
        reader.poll(listOf(coolant, battery), values)
        assertEquals(90.0, values.getValue("BMW_COOLANT"), 1e-6)
        assertEquals("断られた測定値は外して 1 回で読む", 1, p.queries.size)
    }
}
