package com.obdmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ScaleFitTest {
    private fun inside(d: GaugeDesign, tick: Float, q: Float): Boolean {
        val base = 0.5f - ScaleFit.ringWidth(d) - 0.01f
        return (0..48).all { i ->
            val a = (d.startAngle + (d.endAngle - d.startAngle) * i / 48.0 - 90.0) * PI / 180.0
            val x = d.pivotX + tick * base * cos(a)
            val y = d.pivotY + q * tick * base * sin(a)
            sqrt(x * x + y * y) <= base * 0.951
        }
    }

    @Test
    fun centeredKeepsCircle() {
        val d = GaugeDesign(startAngle = -120f, endAngle = 120f)
        val (tick, q) = ScaleFit.fit(d)!!
        assertEquals(1f, q, 0.02f)
        assertEquals(0.95f, tick, 0.02f)
    }

    @Test
    fun offsetPivotFitsInside() {
        val d = GaugeDesign(startAngle = -90f, endAngle = -15f, pivotX = 0.17f, pivotY = 0.04f, ring = RingStyle.BOLD, ringThickness = 2f)
        val r = ScaleFit.fit(d)
        assertNotNull(r)
        val (tick, q) = r!!
        assertTrue(inside(d, tick, q))
        // 真円のままより大きく取れる
        assertTrue(tick > 1f)
    }

    @Test
    fun pivotOutsideFaceIsNull() {
        assertNull(ScaleFit.fit(GaugeDesign(pivotX = 0.5f)))
    }
}
