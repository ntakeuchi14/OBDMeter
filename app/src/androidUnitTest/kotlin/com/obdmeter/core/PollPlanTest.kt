package com.obdmeter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PollPlanTest {
    private fun block(key: String, id: Int) = PidDef(key, key, key, "", 0.0, 1.0, kind = PidDef.Kind.BMW_BLOCK, blockId = id, blockSize = 1)

    @Test
    fun derivedIntervalAppliesToDependencies() {
        val boost = PidDef("B", "b", "b", "", 0.0, 1.0, kind = PidDef.Kind.DERIVED, deps = listOf("P", "BARO"))
        val m = PollPlan.effectiveIntervals(listOf(boost), mapOf("B" to 1_000L, "BARO" to 10_000L))
        assertEquals(1_000L, m["P"])
        assertEquals("依存先に自分の設定があればそちら", 10_000L, m["BARO"])
    }

    @Test
    fun shorterIntervalWinsWhenTwoDerivedShareDependency() {
        val a = PidDef("A", "a", "a", "", 0.0, 1.0, kind = PidDef.Kind.DERIVED, deps = listOf("X"))
        val b = PidDef("B", "b", "b", "", 0.0, 1.0, kind = PidDef.Kind.DERIVED, deps = listOf("X"))
        assertEquals(500L, PollPlan.effectiveIntervals(listOf(a, b), mapOf("A" to 2_000L, "B" to 500L))["X"])
        assertEquals(emptyMap<String, Long>(), PollPlan.effectiveIntervals(listOf(a), emptyMap()))
    }

    @Test
    fun groupsAreOrderedByIntervalWithSequentialNumbers() {
        val items = listOf(block("slow", 1), block("fast", 2), block("mid", 3), block("fast2", 4))
        val g = PollPlan.blockGroups(items, mapOf("slow" to 5_000L, "mid" to 1_000L))
        assertEquals(listOf(0xF0, 0xF1, 0xF2), g.map { it.first })
        assertEquals(listOf("fast", "fast2"), g[0].second.map { it.key })
        assertEquals(listOf("mid"), g[1].second.map { it.key })
        assertEquals(listOf("slow"), g[2].second.map { it.key })
    }

    @Test
    fun tooManyIntervalsAreMergedIntoLastGroup() {
        val items = (1..12).map { block("k$it", it) }
        val iv = items.associate { it.key to it.blockId * 1_000L }
        val g = PollPlan.blockGroups(items, iv)
        assertEquals(PollPlan.MAX_GROUPS, g.size)
        assertEquals(0xF8, g.last().first)
        assertEquals(listOf("k9", "k10", "k11", "k12"), g.last().second.map { it.key })
        assertEquals("記録用の番号は使わない", true, g.none { it.first == PollPlan.LID_SAMPLE })
    }
}
