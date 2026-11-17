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
