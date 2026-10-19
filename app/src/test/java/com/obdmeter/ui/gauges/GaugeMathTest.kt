package com.obdmeter.ui.gauges

import com.obdmeter.core.PidDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeMathTest {
    @Test
    fun tickScaleChoosesRoundSteps() {
        val rpm = TickScale.of(0.0, 8000.0)
        assertEquals(1000.0, rpm.step, 0.0)
        assertEquals(" ×1000", rpm.unitSuffix)
        assertEquals("8", rpm.label(8000.0))
        assertEquals(9, rpm.values(8000.0).size)
        val temp = TickScale.of(40.0, 130.0)
        assertEquals(20.0, temp.step, 0.0)
        assertEquals(40.0, temp.first, 0.0)
        assertEquals("", temp.unitSuffix)
        assertEquals("2.5", TickScale.of(0.0, 20.0).label(2.5))
    }

    @Test
    fun tickScaleHonoursDivisions() {
        val s = TickScale.of(-1.0, 1.5, 5)
        assertEquals(0.5, s.step, 1e-12)
        assertEquals(listOf(-1.0, -0.5, 0.0, 0.5, 1.0, 1.5), s.values(1.5).map { Math.round(it * 10) / 10.0 })
    }

    @Test
    fun ghostTextMatchesWidestValue() {
        assertEquals("8888", ghostText(PidDef("r", "r", "r", "rpm", 0.0, 8000.0)))
        assertEquals("88.8", ghostText(PidDef("v", "v", "v", "V", 10.0, 16.0, decimals = 1)))
        assertEquals("888", ghostText(PidDef("t", "t", "t", "°C", -40.0, 150.0)))
    }

    @Test
    fun smoothingMapsToIntervalAndAlpha() {
        assertEquals(60L, displayIntervalMs(0f))
        assertEquals(DISPLAY_INTERVAL_MS, displayIntervalMs(0.5f))
        assertEquals(340L, displayIntervalMs(1f))
        assertEquals(1.0, smoothingAlpha(0f), 1e-9)
        assertEquals(0.575, smoothingAlpha(0.5f), 1e-6)
        assertEquals(0.15, smoothingAlpha(1f), 1e-6)
        assertEquals("範囲外は丸める", 60L, displayIntervalMs(-1f))
    }

    @Test
    fun fractionAndWarn() {
        val d = PidDef("t", "t", "t", "°C", 40.0, 140.0, warnAbove = 110.0, warnBelow = 50.0)
        assertEquals(0.5f, fraction(d, 90.0), 1e-6f)
        assertEquals(0f, fraction(d, 0.0), 0f)
        assertEquals(1f, fraction(d, 999.0), 0f)
        assertTrue(isWarn(d, 110.0))
        assertTrue(isWarn(d, 45.0))
        assertFalse(isWarn(d, 90.0))
        assertFalse(isWarn(d, null))
    }
}
