package com.obdmeter.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryHistoryStoreTest {
    @Test
    fun storeLoadAndNeighbors() = runBlocking {
        val h = MemoryHistoryStore()
        for (i in 0 until 10) h.store(mapOf("g" to mapOf("RPM" to i * 100.0)), 1_000_000L + i * 1000L + 300)
        val all = h.load("g", "RPM", 1_000_000L, 1_009_000L, 100)
        assertEquals(10, all.size)
        assertEquals(1_000_000L, all.first().t)
        val (before, after) = h.neighbors("g", "RPM", 1_003_000L, 1_005_000L)
        assertEquals(1_002_000L, before!!.t)
        assertEquals(1_006_000L, after!!.t)
        // まとめると最小と最大が残る
        val few = h.load("g", "RPM", 1_000_000L, 1_009_000L, 4)
        assertTrue(few.size < 10)
        assertEquals(0f, few.minOf { it.v })
        assertEquals(900f, few.maxOf { it.v })
        assertEquals(1_000_000L, h.earliest("g", listOf("RPM", "X")))
    }

    @Test
    fun csvRoundTrip() = runBlocking {
        val h = MemoryHistoryStore()
        h.store(mapOf("g" to mapOf("A" to 1.5, "B" to 2.0)), 1_700_000_000_000L)
        val (n, text) = h.exportCsvText("g", listOf("A", "B"))
        assertEquals(2, n)
        val h2 = MemoryHistoryStore()
        val (ok, bad) = h2.importCsvText("x", text)
        assertEquals(2, ok)
        assertEquals(0, bad)
        assertEquals(1.5, h2.lastRow("x", "A")!!.first.v, 0.0)
    }

    @Test
    fun changeRowsAndClear() = runBlocking {
        val h = MemoryHistoryStore()
        h.storeRows(listOf(HistoryRow("g", "A", 10_000, 1.0, false), HistoryRow("g", "A", 20_000, 1.0, true)))
        val (last, tail) = h.lastRow("g", "A")!!
        assertEquals(20_000L, last.t)
        assertTrue(tail)
        h.storeRows(listOf(HistoryRow("g", "A", 30_000, 1.0, true, replaceT = 20_000)))
        assertEquals(2, h.load("g", "A", 0, 100_000, 100).size)
        assertEquals(listOf(10_000L to 30_000L), h.segments("g", listOf("A"), 0, 100_000, 5_000))
        h.clear("g")
        assertNull(h.lastRow("g", "A"))
        assertEquals(setOf("g"), h.takeCleared())
    }
}
