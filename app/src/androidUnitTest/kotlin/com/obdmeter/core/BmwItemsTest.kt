package com.obdmeter.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwItemsTest {
    @Test
    fun everyTableLineBecomesAnItem() {
        val lines = MSD80_TABLE.flatMap { it.lines() }.filter { it.isNotBlank() }
        assertEquals(lines.size, BmwItems.msd80Items().size)
        val status = MSD80_STATUS_TABLE.flatMap { it.lines() }.filter { it.isNotBlank() }
        assertEquals(status.size, BmwItems.msd80StatusItems().size)
        assertTrue(lines.size > 600 && status.size > 500)
    }

    @Test
    fun keysAreUniqueAcrossCatalog() {
        val dup = Pids.ALL.groupBy { it.key }.filter { it.value.size > 1 }.keys
        assertEquals(emptySet<String>(), dup)
    }

    @Test
    fun allBmwItemsAreListedAsBmwSpecific() {
        assertTrue(BmwItems.ALL.all { Pids.isBmwSpecific(it) })
        assertTrue(Pids.ALL.filter { it.key in setOf("RPM", "SPEED", "COOLANT") }.none { Pids.isBmwSpecific(it) })
    }

    @Test
    fun measurementItemsHaveConsistentSizes() {
        for (d in BmwItems.msd80Items()) {
            assertEquals(PidDef.Kind.BMW_BLOCK, d.kind)
            assertTrue(d.key, d.blockSize in setOf(1, 2, 4) && d.minBytes == d.blockSize)
            assertTrue(d.key, d.max > d.min)
        }
    }

    @Test
    fun statusItemsFitInsideTheirResponse() {
        for (d in BmwItems.msd80StatusItems()) {
            val req = d.request!!
            assertTrue(d.key, req.mode == 0x30 && req.pid[1] == 0x01 || req.mode == 0x22)
            // 必要なバイト数ちょうどの 0 を渡しても例外にならない
            d.decode(IntArray(d.minBytes))
        }
    }

    @Test
    fun decodesKnownMeasurements() {
        val iat = Pids.get("MSD80_4204")!!                       // 外気温 u8 × 0.75 − 48
        assertEquals(42.0, iat.decode(intArrayOf(120)), 1e-3)
        val oilPressure = Pids.get("BMW_OIL_PRESSURE")!!         // s16 / 1000 bar
        assertEquals(-0.5, oilPressure.decode(intArrayOf(0xFE, 0x0C)), 1e-9)
        val misfire = Pids.get("MISFIRE_LIFE_5")!!
        assertEquals(0x4851, misfire.blockId)                    // 点火順 1-5-3-6-2-4
    }

    @Test
    fun blockRequestDefinesEntriesInOrder() {
        val a = Pids.get("BMW_COOLANT")!!
        val b = Pids.get("BMW_RPM")!!
        val r = Pids.bmwBlockRequest(listOf(a, b), 0xF3)
        assertEquals(0x21, r.mode)
        assertArrayEquals(intArrayOf(0xF3), r.pid)
        assertArrayEquals(intArrayOf(0x2C, 0xF3, 0x04), r.setup[0])
        assertArrayEquals(intArrayOf(0x2C, 0xF3, 0x02, 1, 1, 0x58, 0x1F, 0x01, 0x02, 2, 2, 0x48, 0x07, 0x01), r.setup[1])
        assertEquals("6F1", r.header)
        assertEquals("12", r.canExtAddress)
    }

    @Test
    fun boostUsesStandardPressureWhenBaroMissingOrInvalid() {
        assertEquals(500.0, BmwItems.boostHpa(mapOf("MSD80_5A2C" to 1513.25))!!, 1e-9)
        assertEquals(500.0, BmwItems.boostHpa(mapOf("MSD80_5A2C" to 1513.25, "MSD80_4201" to 0.0))!!, 1e-9)
        assertEquals(400.0, BmwItems.boostHpa(mapOf("MSD80_5A2C" to 1400.0, "MSD80_4201" to 1000.0))!!, 1e-9)
        assertEquals(null, BmwItems.boostHpa(emptyMap()))
    }

    @Test
    fun exhaustFlapIsBinary() {
        val f = Pids.get("BMW_EXHAUST_FLAP")!!
        assertEquals(1.0, f.decode(intArrayOf(5)), 0.0)
        assertEquals(0.0, f.decode(intArrayOf(0)), 0.0)
    }
}
