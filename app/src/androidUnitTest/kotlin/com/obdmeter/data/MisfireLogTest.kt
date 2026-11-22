package com.obdmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MisfireLogTest {
    private fun vals(vararg v: Double) = v.withIndex().associate { (i, x) -> "MISFIRE_${i + 1}" to x }

    @Test
    fun firstValueIsBaselineAndIncreasesAreRecordedNewestFirst() {
        val t = MisfireLog.Tracker(MisfireLog.Source.OBD)
        assertFalse("最初の値は基準", t.observe(vals(5.0, 0.0, 0.0, 0.0, 0.0, 0.0), 1000))
        assertTrue(t.events.isEmpty())
        assertTrue(t.observe(vals(5.0, 0.0, 1.0, 0.0, 0.0, 0.0), 2000))
        assertTrue(t.observe(vals(7.0, 0.0, 1.0, 0.0, 0.0, 0.0), 3000))
        assertEquals(listOf(MisfireLog.Event(1, 3000, 2), MisfireLog.Event(3, 2000, 1)), t.events)
    }

    @Test
    fun decreaseOnlyMovesBaselineAndResetForgetsEverything() {
        val t = MisfireLog.Tracker(MisfireLog.Source.OBD)
        t.observe(vals(3.0, 3.0, 3.0, 3.0, 3.0, 3.0), 0)
        assertFalse(t.observe(vals(0.0, 3.0, 3.0, 3.0, 3.0, 3.0), 1))
        assertTrue(t.observe(vals(1.0, 3.0, 3.0, 3.0, 3.0, 3.0), 2))
        t.reset()
        assertTrue(t.events.isEmpty())
        assertFalse("再接続後の最初の値は基準", t.observe(vals(9.0, 9.0, 9.0, 9.0, 9.0, 9.0), 3))
    }

    @Test
    fun missingKeysAreSkippedAndListIsCapped() {
        val t = MisfireLog.Tracker(MisfireLog.Source.BMW)
        t.observe(mapOf("MISFIRE_LIFE_6" to 0.0), 0)
        for (i in 1..80) t.observe(mapOf("MISFIRE_LIFE_6" to i.toDouble()), i.toLong())
        assertEquals(MisfireLog.MAX_EVENTS, t.events.size)
        assertEquals("件数は残す件数を超えても数える", 80, t.total)
        assertEquals(6, t.events.first().cyl)
        assertEquals(80L, t.events.first().time)
    }

    @Test
    fun sourceFollowsItem() {
        assertEquals(MisfireLog.Source.BMW, MisfireLog.sourceOf(com.obdmeter.core.DeviceItems.MISFIRE_LOG_LIFE))
        assertEquals(MisfireLog.Source.OBD, MisfireLog.sourceOf(com.obdmeter.core.DeviceItems.MISFIRE_LOG))
        // 0:00:05 UTC = 9:00:05 JST
        assertEquals("#2 09:00:05 +3", MisfireLog.format(MisfireLog.Event(2, 5000, 3)))
        assertEquals("#2 09:00:05", MisfireLog.format(MisfireLog.Event(2, 5000, 1)))
    }

    @Test
    fun itemValueIsEventCountAndZeroShowsDashes() {
        val v = MisfireLog.values(mapOf(MisfireLog.Source.OBD to 2))
        assertEquals(2.0, v[com.obdmeter.core.DeviceItems.MISFIRE_LOG]!!, 0.0)
        assertEquals(0.0, v[com.obdmeter.core.DeviceItems.MISFIRE_LOG_LIFE]!!, 0.0)
        val def = com.obdmeter.core.Pids.get(com.obdmeter.core.DeviceItems.MISFIRE_LOG)!!
        assertEquals("--", def.format(0.0))
        assertEquals("2", def.format(2.0))
    }

    @Test
    fun readPlanWatchesCountersOfMisfireLamp() {
        val g = GaugeConfig(id = 1, pidKey = com.obdmeter.core.DeviceItems.MISFIRE_LOG_LIFE, style = GaugeStyle.LAMP, x = 0, y = 0, w = 1, h = 1)
        assertTrue("MISFIRE_LIFE_1" in ReadPlan.gaugeKeys(g))
        assertEquals(setOf(MisfireLog.Source.BMW), ReadPlan.misfireSources(listOf(Dashboard(id = 1, name = "p", gauges = listOf(g)))))
    }
}
