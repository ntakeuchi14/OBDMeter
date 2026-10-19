package com.obdmeter.data

import com.obdmeter.core.PidCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadPlanTest {
    private val chart = GaugeConfig(
        pidKey = "RPM", style = GaugeStyle.CHART, id = 1, chartKeys = listOf("SPEED", "COOLANT"),
        design = GaugeDesign(recordIntervalSec = 10f),
    )
    private val shownOnlyChart = GaugeConfig(
        pidKey = "BMW_COOLANT", style = GaugeStyle.CHART, id = 2, chartKeys = listOf("RPM"),
        design = GaugeDesign(recordIntervalSec = 2f, recordOnlyWhenShown = true, historyHours = 24),
    )
    private val meter = GaugeConfig(pidKey = "RPM", id = 3, fallbackKey = "BMW_RPM")
    private val table = GaugeConfig(pidKey = "LOAD", style = GaugeStyle.TABLE, id = 4, chartKeys = listOf("RPM", "MAP"))
    private val pages = listOf(
        Dashboard(1, "メイン", gauges = listOf(meter, table)),
        Dashboard(2, "グラフ", gauges = listOf(chart)),
        Dashboard(3, "表示中だけ", gauges = listOf(shownOnlyChart)),
    )

    @Test
    fun shownPagesFollowAppOrFloat() {
        assertEquals(listOf(1L), ReadPlan.shownPages(pages, appVisible = true, appPage = 1, floatPage = 2).map { it.id })
        assertEquals(listOf(2L), ReadPlan.shownPages(pages, appVisible = false, appPage = 1, floatPage = 2).map { it.id })
        assertEquals("フロートのページが無ければアプリのページ", listOf(1L),
            ReadPlan.shownPages(pages, appVisible = false, appPage = 1, floatPage = null).map { it.id })
    }

    @Test
    fun activeItemsAreUniqueAndIncludeTableItems() {
        val (pids, fallbacks) = ReadPlan.activeItems(listOf(pages[0]), PidCatalog())
        assertEquals(listOf("RPM", "LOAD", "MAP"), pids.map { it.key })
        assertEquals(mapOf("RPM" to "BMW_RPM"), fallbacks.mapValues { it.value.key })
    }

    @Test
    fun recordIntervalsUseShortestAndRespectShownOnly() {
        val hidden = ReadPlan.recordIntervals(pages, shownPage = 1)
        assertEquals(mapOf("RPM" to 10_000L, "SPEED" to 10_000L, "COOLANT" to 10_000L), hidden)
        val shown = ReadPlan.recordIntervals(pages, shownPage = 3)
        assertEquals(2_000L, shown["RPM"])
        assertEquals(2_000L, shown["BMW_COOLANT"])
        assertEquals(10_000L, shown["SPEED"])
    }

    @Test
    fun retentionIsLongestChart() {
        assertEquals(3 * HistoryStore.MONTH_MS, ReadPlan.retentionMs(pages))
        assertEquals(24 * 3_600_000L, ReadPlan.retentionMs(listOf(pages[2])))
        assertEquals("グラフが無ければ 3 か月", 3 * HistoryStore.MONTH_MS, ReadPlan.retentionMs(listOf(pages[0])))
    }

    @Test
    fun splitSampleReusesLiveValuesOfActiveItems() {
        val (reuse, need) = ReadPlan.splitSample(
            listOf("RPM", "SPEED", "COOLANT"),
            live = mapOf("RPM" to 3000.0, "COOLANT" to 90.0),
            activeKeys = setOf("RPM", "SPEED"),
        )
        assertEquals(mapOf("RPM" to 3000.0), reuse)
        assertEquals("表示中でも値が無い項目と、表示していない項目は読む", listOf("SPEED", "COOLANT"), need)
    }

    @Test
    fun gaugeKeysForEachStyle() {
        assertEquals(listOf("RPM"), ReadPlan.gaugeKeys(meter))
        assertEquals(listOf("RPM", "SPEED", "COOLANT"), ReadPlan.gaugeKeys(chart))
        assertEquals(listOf("LOAD", "RPM", "MAP"), ReadPlan.gaugeKeys(table))
    }
}
