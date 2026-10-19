package com.obdmeter.data

import org.junit.Assert.assertArrayEquals
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
}
