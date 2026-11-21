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

class LayoutGroupOrderTest {
    private fun g(id: Long, group: Long? = null) = GaugeConfig("RPM", id = id, layoutGroup = group)

    @Test
    fun groupsBecomeContiguousAtFrontmostMember() {
        // 1(G) 2 3(G) 4 → 2 1 3 4 (グループは手前のメンバー 3 の位置にまとまる)
        val r = Layout.compactGroups(listOf(g(1, 9), g(2), g(3, 9), g(4)))
        assertEquals(listOf(2L, 1L, 3L, 4L), r.map { it.id })
    }

    @Test
    fun singleJumpsOverWholeGroup() {
        val list = listOf(g(1), g(2, 9), g(3, 9), g(4))
        assertEquals(listOf(2L, 3L, 1L, 4L), Layout.moveZ(list, 1, 1).map { it.id })
    }

    @Test
    fun memberMovesInsideGroupThenGroupMoves() {
        val list = listOf(g(1), g(2, 9), g(3, 9), g(4))
        // グループの中で入れ替え
        assertEquals(listOf(1L, 3L, 2L, 4L), Layout.moveZ(list, 2, 1).map { it.id })
        // グループの手前の端からさらに手前へ → グループごと
        assertEquals(listOf(1L, 4L, 2L, 3L), Layout.moveZ(list, 3, 1).map { it.id })
        // 最背面 → グループごと
        assertEquals(listOf(2L, 3L, 1L, 4L), Layout.moveZ(list, 3, Int.MIN_VALUE).map { it.id })
    }
}
