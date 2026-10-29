package com.obdmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorCaptureTest {
    private fun snap(i: Int) = MonitorCapture.Snapshot(1_000L * i, mapOf("MIS" to 38.0 + (if (i >= 7) 1 else 0), "RPM" to 800.0 + i))

    @Test
    fun writesFiveBeforeAndFiveAfter() {
        val c = MonitorCapture { _, v -> v.toLong().toString() }
        val out = ArrayList<MonitorCapture.File>()
        for (i in 0 until 20) {
            val ev = if (i == 7) listOf("MIS" to 39.0) else emptyList()
            out += c.observe("1:2", snap(i), ev, listOf("RPM"))
            if (i < 12) assertTrue(out.isEmpty())
        }
        assertEquals(1, out.size)
        val f = out[0]
        assertEquals("1:2", f.slot)
        assertTrue(f.name, f.name.startsWith("MIS-") && f.name.endsWith("-39.csv"))
        val lines = f.text.trim().lines()
        assertEquals("相対,日時,MIS,RPM", lines[0])
        assertEquals(11, lines.size - 1)
        assertEquals((-5..5).map { it.toString() }, lines.drop(1).map { it.substringBefore(',') })
        // 検知した回の値
        assertTrue(lines[6].endsWith(",39,807"))
    }

    @Test
    fun flushWritesPartialAndForgetDrops() {
        val c = MonitorCapture()
        c.observe("a", snap(0), emptyList(), listOf("RPM"))
        c.observe("a", snap(1), listOf("MIS" to 39.0), listOf("RPM"))
        c.observe("a", snap(2), emptyList(), listOf("RPM"))
        val f = c.flush()
        assertEquals(1, f.size)
        assertEquals(listOf("-1", "0", "1"), f[0].text.trim().lines().drop(1).map { it.substringBefore(',') })

        c.observe("b", snap(0), listOf("MIS" to 1.0), listOf("RPM"))
        c.forget("b")
        assertTrue(c.flush().isEmpty())
    }

    @Test
    fun fileNameIsSafe() {
        assertEquals("A_B-19700101_000000-1.5.csv".substring(0, 4), MonitorCapture.fileName("A/B", 0, "1.5").substring(0, 4))
        assertTrue(MonitorCapture.fileName("K", 0, "-3").endsWith("--3.csv"))
    }
}
