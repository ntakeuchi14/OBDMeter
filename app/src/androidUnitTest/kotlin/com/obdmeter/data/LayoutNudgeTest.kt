package com.obdmeter.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutNudgeTest {
    private val a = GaugeConfig("RPM", id = 1, x = 8, y = 8, w = 16, h = 16)

    @Test
    fun movesByOneUnitAndClamps() {
        assertArrayEquals(intArrayOf(9, 8, 16, 16), Layout.nudge(listOf(a), 1, 1, 0, false)[1])
        val edge = a.copy(x = Layout.COLS - Layout.SCALE)
        assertEquals(Layout.COLS - Layout.SCALE, Layout.nudge(listOf(edge), 1, 1, 0, false)[1]!![0])
    }

    @Test
    fun resizesWithinLimits() {
        assertArrayEquals(intArrayOf(8, 8, 17, 15), Layout.nudge(listOf(a), 1, 1, -1, true)[1])
        val tiny = a.copy(w = Layout.MIN_SIZE)
        assertEquals(Layout.MIN_SIZE, Layout.nudge(listOf(tiny), 1, -1, 0, true)[1]!![2])
    }

    @Test
    fun movesWholeGroup() {
        val g1 = a.copy(layoutGroup = 5L)
        val g2 = GaugeConfig("SPEED", id = 2, x = 30, y = 8, w = 8, h = 8, layoutGroup = 5L)
        val p = Layout.nudge(listOf(g1, g2), 1, 0, 1, false)
        assertEquals(9, p[1]!![1]); assertEquals(9, p[2]!![1])
        // 大きさ変更はそのメーターだけ
        assertEquals(setOf(1L), Layout.nudge(listOf(g1, g2), 1, 1, 0, true).keys)
    }
}

class LayoutUnitScaleTest {
    @Test
    fun oldUnitsAreScaled() {
        val d = Dashboard(id = 1, name = "p", gauges = listOf(GaugeConfig("RPM", id = 1, x = 2, y = 1, w = 6, h = 6)), unitScale = 1)
        val n = Layout.normalize(d)
        assertEquals(2 * Layout.SCALE, n.gauges[0].x)
        assertEquals(Layout.SCALE, n.unitScale)
    }

    @Test
    fun alreadyFineUnitsWithMissingRecordAreKept() {
        // 単位の記録が抜けていた (1 のまま) が、位置はすでに細かい単位のページ
        val d = Dashboard(id = 1, name = "p", gauges = listOf(GaugeConfig("RPM", id = 1, x = 256, y = 16, w = 80, h = 72)), unitScale = 1)
        val n = Layout.normalize(d)
        assertEquals(256, n.gauges[0].x)
        assertEquals(80, n.gauges[0].w)
        assertEquals(Layout.SCALE, n.unitScale)
    }
}
