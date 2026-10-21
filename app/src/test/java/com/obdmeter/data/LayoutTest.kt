package com.obdmeter.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    @Test
    fun keepsPlacementInsidePage() {
        assertArrayEquals(intArrayOf(3, 4, 6, 5), Layout.clampPlacement(3, 4, 6, 5))
    }

    @Test
    fun allowsOverflowButKeepsOneCellInside() {
        assertArrayEquals(intArrayOf(-5, 0, 6, 4), Layout.clampPlacement(-5, 0, 6, 4))
        assertArrayEquals("左・上は 1 マス残す", intArrayOf(-5, -3, 6, 4), Layout.clampPlacement(-20, -20, 6, 4))
        assertArrayEquals("右・下も 1 マス残す", intArrayOf(Layout.COLS - 1, Layout.ROWS - 1, 6, 4), Layout.clampPlacement(99, 99, 6, 4))
    }

    @Test
    fun clampsSize() {
        assertArrayEquals(intArrayOf(0, 0, Layout.MIN_SIZE, Layout.MIN_SIZE), Layout.clampPlacement(0, 0, 0, -3))
        assertArrayEquals(intArrayOf(0, 0, Layout.COLS * 2, Layout.ROWS * 2), Layout.clampPlacement(0, 0, 999, 999))
    }

    private fun g(id: Long, x: Int, y: Int, w: Int, h: Int, group: Long? = 9) =
        GaugeConfig(pidKey = "RPM", id = id, x = x, y = y, w = w, h = h, layoutGroup = group)

    @Test
    fun groupsNeedTwoMembers() {
        val gs = listOf(g(1, 0, 0, 4, 4), g(2, 4, 0, 4, 4), g(3, 0, 4, 4, 4, group = 5), g(4, 8, 0, 2, 2, group = null))
        assertEquals(setOf(9L), Layout.groups(gs).keys)
        assertArrayEquals(intArrayOf(0, 0, 8, 4), Layout.bounds(Layout.groups(gs).getValue(9)))
    }

    @Test
    fun moveGroupKeepsRelativePlacement() {
        val gs = listOf(g(1, 0, 0, 4, 4), g(2, 4, 2, 4, 4))
        val m = Layout.moveGroup(gs, 3, 1)
        assertArrayEquals(intArrayOf(3, 1, 4, 4), m.getValue(1))
        assertArrayEquals(intArrayOf(7, 3, 4, 4), m.getValue(2))
        // ページの外へ出きらない (グループの右端に 1 マス残る位置まで)
        val far = Layout.moveGroup(gs, -100, 0)
        assertEquals(1 - 8, far.getValue(1)[0])
    }

    @Test
    fun resizeGroupScalesFromTopLeft() {
        val gs = listOf(g(1, 2, 2, 4, 4), g(2, 6, 2, 4, 4))
        val r = Layout.resizeGroup(gs, 16, 8)  // 8×4 → 16×8 (2 倍)
        assertArrayEquals(intArrayOf(2, 2, 8, 8), r.getValue(1))
        assertArrayEquals(intArrayOf(10, 2, 8, 8), r.getValue(2))
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
