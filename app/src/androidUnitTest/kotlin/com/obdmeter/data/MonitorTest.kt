package com.obdmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorTest {
    private fun st(key: String, vararg counts: Int, first: Double? = 0.0, last: Double? = 0.0) =
        Monitor.Stat(key, first, last, null, counts.toList())

    @Test
    fun ranksByCountThenRegistrationOrder() {
        val stats = listOf(st("A", 1, 5), st("B", 3, 5), st("C", 1, 9))
        assertEquals(listOf("B", "A", "C"), Monitor.rank(stats, 0).map { it.key })
        assertEquals("同じ回数なら登録順", listOf("C", "A", "B"), Monitor.rank(stats, 1).map { it.key })
    }

    @Test
    fun currentValueIsLatestMinusFirst() {
        val s = st("A", first = 10.0, last = 14.0)
        assertEquals(4.0, Monitor.currentValue(s, null)!!, 1e-9)
        assertEquals("今読めている値を優先", 7.0, Monitor.currentValue(s, 17.0)!!, 1e-9)
        assertNull(Monitor.currentValue(st("B", first = null, last = null), 3.0))
    }

    @Test
    fun recordsOnlyChanges() {
        assertEquals(true, Monitor.shouldRecord(1.0, null))
        assertEquals(false, Monitor.shouldRecord(1.0, 1.0))
        assertEquals(true, Monitor.shouldRecord(2.0, 1.0))
    }

    @Test
    fun increaseOnlyIgnoresDrops() {
        // 39 → 38 (一時的に古い値) → 39 は記録しない、40 は記録する
        assertEquals(true, Monitor.shouldRecord(38.0, null, increaseOnly = true))
        assertEquals(false, Monitor.shouldRecord(38.0, 39.0, increaseOnly = true))
        assertEquals(false, Monitor.shouldRecord(39.0, 39.0, increaseOnly = true))
        assertEquals(true, Monitor.shouldRecord(40.0, 39.0, increaseOnly = true))
    }

    @Test
    fun windowIndexFindsPeriodOrFallsBackToOneDay() {
        assertEquals("10秒", Monitor.WINDOWS[Monitor.windowIndex(10_000L)].second)
        assertEquals("1日", Monitor.WINDOWS[Monitor.windowIndex(12_345L)].second)
    }
}
