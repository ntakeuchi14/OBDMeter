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

    private fun target(page: Long, gauge: Long, key: String) = ReadPlan.RecordTarget(HistorySlot.of(page, gauge), key)

    @Test
    fun recordRulesArePerChartAndRespectShownOnly() {
        val hidden = ReadPlan.recordRules(pages, shownPage = 1)
        assertEquals(
            setOf(target(2, 1, "RPM"), target(2, 1, "SPEED"), target(2, 1, "COOLANT")),
            hidden.keys,
        )
        assertEquals(10_000L, hidden.getValue(target(2, 1, "RPM")).intervalMs)
        val shown = ReadPlan.recordRules(pages, shownPage = 3)
        assertEquals("同じ項目でもグラフごとに別の記録・別の間隔", 10_000L, shown.getValue(target(2, 1, "RPM")).intervalMs)
        assertEquals(2_000L, shown.getValue(target(3, 2, "RPM")).intervalMs)
        assertEquals(2_000L, shown.getValue(target(3, 2, "BMW_COOLANT")).intervalMs)
    }

    @Test
    fun changeOnlyChartUsesPollInterval() {
        val onChange = GaugeConfig(
            pidKey = "OIL", style = GaugeStyle.CHART, id = 5, chartKeys = listOf("SPEED", "IAT"),
            design = GaugeDesign(recordOnChange = true),
        )
        val ps = pages + Dashboard(4, "変化だけ", gauges = listOf(onChange))
        val rules = ReadPlan.recordRules(ps, shownPage = 1, pollIntervals = mapOf("OIL" to 30_000L))
        assertEquals(ReadPlan.RecordRule(30_000L, true), rules[target(4, 5, "OIL")])
        assertEquals("取得周期が無ければ最短で見る", ReadPlan.RecordRule(ReadPlan.CHANGE_MIN_MS, true), rules[target(4, 5, "IAT")])
        assertEquals("ほかのグラフの同じ項目は自分の設定のまま", ReadPlan.RecordRule(10_000L, false), rules[target(2, 1, "SPEED")])
    }

    @Test
    fun retentionsAndKeysArePerChart() {
        assertEquals(
            mapOf(HistorySlot.of(2, 1) to 3 * HistoryStore.MONTH_MS, HistorySlot.of(3, 2) to 24 * 3_600_000L),
            ReadPlan.retentions(pages),
        )
        assertEquals(listOf("BMW_COOLANT", "RPM"), ReadPlan.chartKeys(pages)[HistorySlot.of(3, 2)])
        assertEquals("グラフが無ければ空", emptyMap<String, Long>(), ReadPlan.retentions(listOf(pages[0])))
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
