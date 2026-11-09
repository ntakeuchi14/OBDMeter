package com.obdmeter.data

import com.obdmeter.DisplayLight
import org.junit.Assert.assertEquals
import org.junit.Test

class GlowLinkRuleTest {
    @Test
    fun rules() {
        val s = AppSettings()
        assertEquals(null, GlowLinkRule.lightOn(DisplayLight(true, 10), s))
        assertEquals(true, GlowLinkRule.lightOn(DisplayLight(true, 200), s.copy(glowLink = GlowLink.NIGHT_MODE)))
        assertEquals(false, GlowLinkRule.lightOn(DisplayLight(false, 10), s.copy(glowLink = GlowLink.NIGHT_MODE)))
        val b = s.copy(glowLink = GlowLink.BRIGHTNESS, glowLinkBrightness = 100)
        assertEquals(true, GlowLinkRule.lightOn(DisplayLight(false, 60), b))
        assertEquals(false, GlowLinkRule.lightOn(DisplayLight(false, 200), b))
        assertEquals(null, GlowLinkRule.lightOn(DisplayLight(false, null), b))
    }
}
