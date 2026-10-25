package com.obdmeter.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    /** 旧来の 1 マス */
    private val S = Layout.SCALE

    @Test
    fun keepsPlacementInsidePage() {
        assertArrayEquals(intArrayOf(3 * S, 4 * S, 6 * S, 5 * S), Layout.clampPlacement(3 * S, 4 * S, 6 * S, 5 * S))
    }

    @Test
    fun allowsOverflowButKeepsOneCellInside() {
        assertArrayEquals(intArrayOf(-5 * S, 0, 6 * S, 4 * S), Layout.clampPlacement(-5 * S, 0, 6 * S, 4 * S))
        assertArrayEquals("左・上は 1 マス残す", intArrayOf(-5 * S, -3 * S, 6 * S, 4 * S), Layout.clampPlacement(-999, -999, 6 * S, 4 * S))
        assertArrayEquals("右・下も 1 マス残す", intArrayOf(Layout.COLS - S, Layout.ROWS - S, 6 * S, 4 * S), Layout.clampPlacement(999, 999, 6 * S, 4 * S))
    }

    @Test
    fun clampsSize() {
        assertArrayEquals(intArrayOf(0, 0, Layout.MIN_SIZE, Layout.MIN_SIZE), Layout.clampPlacement(0, 0, 0, -3))
        assertArrayEquals(intArrayOf(0, 0, Layout.COLS * 3, Layout.ROWS * 3), Layout.clampPlacement(0, 0, 9999, 9999))
    }

    @Test
    fun snapsToGridStep() {
        assertEquals(8, Layout.snap(9, 4))
        assertEquals(12, Layout.snap(10, 4))
        assertEquals(9, Layout.snap(9, 1))
        assertEquals(-4, Layout.snap(-5, 4))
    }

    @Test
    fun normalizeConvertsOldUnitsOnce() {
        val old = Dashboard(1, "p", gauges = listOf(GaugeConfig("RPM", id = 1, x = 2, y = 3, w = 6, h = 4)))
        val n = Layout.normalize(old)
        assertEquals(S, n.unitScale)
        val g = n.gauges.single()
        assertArrayEquals(intArrayOf(2 * S, 3 * S, 6 * S, 4 * S), intArrayOf(g.x, g.y, g.w, g.h))
        assertEquals("2 回目は変わらない", n, Layout.normalize(n))
    }

    private fun g(id: Long, x: Int, y: Int, w: Int, h: Int, group: Long? = 9) =
        GaugeConfig(pidKey = "RPM", id = id, x = x * S, y = y * S, w = w * S, h = h * S, layoutGroup = group)

    @Test
    fun groupsNeedTwoMembers() {
        val gs = listOf(g(1, 0, 0, 4, 4), g(2, 4, 0, 4, 4), g(3, 0, 4, 4, 4, group = 5), g(4, 8, 0, 2, 2, group = null))
        assertEquals(setOf(9L), Layout.groups(gs).keys)
        assertArrayEquals(intArrayOf(0, 0, 8 * S, 4 * S), Layout.bounds(Layout.groups(gs).getValue(9)))
    }

    @Test
    fun moveGroupKeepsRelativePlacement() {
        val gs = listOf(g(1, 0, 0, 4, 4), g(2, 4, 2, 4, 4))
        val m = Layout.moveGroup(gs, 3 * S, 1 * S)
        assertArrayEquals(intArrayOf(3 * S, 1 * S, 4 * S, 4 * S), m.getValue(1))
        assertArrayEquals(intArrayOf(7 * S, 3 * S, 4 * S, 4 * S), m.getValue(2))
        // ページの外へ出きらない (グループの右端に 1 マス残る位置まで)
        val far = Layout.moveGroup(gs, -9999, 0)
        assertEquals(S - 8 * S, far.getValue(1)[0])
    }

    @Test
    fun resizeGroupScalesFromTopLeft() {
        val gs = listOf(g(1, 2, 2, 4, 4), g(2, 6, 2, 4, 4))
        val r = Layout.resizeGroup(gs, 16 * S, 8 * S)  // 8×4 → 16×8 (2 倍)
        assertArrayEquals(intArrayOf(2 * S, 2 * S, 8 * S, 8 * S), r.getValue(1))
        assertArrayEquals(intArrayOf(10 * S, 2 * S, 8 * S, 8 * S), r.getValue(2))
        // 縮めても最小の大きさを下回らない
        val small = Layout.resizeGroup(gs, 1, 1)
        assertTrue(small.values.all { it[2] >= Layout.MIN_SIZE && it[3] >= Layout.MIN_SIZE })
    }

    @Test
    fun pasteOffsetKeepsPlaceWhenFree() {
        val src = listOf(g(1, 2, 2, 4, 4), g(2, 6, 2, 4, 4))
        assertEquals(0 to 0, Layout.pasteOffset(emptyList(), src))
        val (dx, dy) = Layout.pasteOffset(src, src)
        val moved = src.map { it.copy(x = it.x + dx, y = it.y + dy) }
        assertTrue("空いている場所に重ならずに置く", moved.none { a -> src.any { b -> a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h } })
    }
}
