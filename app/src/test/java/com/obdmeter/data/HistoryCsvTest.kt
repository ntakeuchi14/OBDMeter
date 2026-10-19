package com.obdmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCsvTest {
    @Test
    fun writtenLineReadsBack() {
        val t = HistoryCsv.parseLine("RPM,2026-10-19 12:34:56,3000")!!.second
        val line = HistoryCsv.line("RPM", t, 3000.0)
        assertEquals("RPM,2026-10-19 12:34:56,3000", line)
        assertEquals(Triple("RPM", t, 3000.0), HistoryCsv.parseLine(line))
    }

    @Test
    fun acceptsOtherDateFormatsAndQuotes() {
        val base = HistoryCsv.parseLine("A,2026-01-02 03:04:05,1")!!.second
        assertEquals(base, HistoryCsv.parseLine("A,2026/01/02 03:04:05,1")!!.second)
        assertEquals(base, HistoryCsv.parseLine("A,2026-01-02T03:04:05,1")!!.second)
        assertEquals(Triple("A", base, 1.5), HistoryCsv.parseLine("\"A\", \"2026-01-02 03:04:05\" , 1.5"))
    }

    @Test
    fun rejectsBrokenLines() {
        for (bad in listOf("", "A,2026-01-02 03:04:05", ",2026-01-02 03:04:05,1", "A,yesterday,1",
            "A,2026-13-40 99:99:99,1", "A,2026-01-02 03:04:05,abc", "A,2026-01-02 03:04:05,NaN")) {
            assertNull(bad, HistoryCsv.parseLine(bad))
        }
    }

    @Test
    fun formatsValues() {
        assertEquals("12", HistoryCsv.formatValue(12.0))
        assertEquals("-3", HistoryCsv.formatValue(-3.0))
        assertEquals("0.25", HistoryCsv.formatValue(0.25))
        assertTrue(HistoryCsv.isHeader(HistoryCsv.HEADER))
        assertTrue(HistoryCsv.isHeader("Key,datetime,value"))
    }

    @Test
    fun mergesNearbyBucketsIntoSegments() {
        val b = listOf(0L to 10L, 15L to 30L, 100L to 120L, 125L to 125L, 500L to 510L)
        assertEquals(listOf(0L to 30L, 100L to 125L, 500L to 510L), HistoryCsv.mergeSegments(b, gapMs = 10))
        assertEquals(listOf(0L to 510L), HistoryCsv.mergeSegments(b, gapMs = 1_000))
        assertEquals(emptyList<Pair<Long, Long>>(), HistoryCsv.mergeSegments(emptyList(), 10))
    }
}
