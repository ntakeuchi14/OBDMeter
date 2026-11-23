package com.obdmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GlowOffDesignTest {
    private val on = GaugeDesign(valueColor = 0xFFFFFFFF, backgroundImage = "a.png", valueFont = "f1")
    private val off = GaugeDesign(valueColor = 0xFFFF0000, backgroundImage = "b.png", labelFont = "f2")

    @Test
    fun glowOffUsesSecondDesignOnlyWhenPresentAndSupported() {
        val g = GaugeConfig(pidKey = "RPM", style = GaugeStyle.ANALOG, design = on, glowOffDesign = off)
        assertEquals(off, g.forGlow(true).design)
        assertEquals(on, g.forGlow(false).design)
        val single = g.copy(glowOffDesign = null)
        assertSame(single, single.forGlow(true))
        // チャートなどは発光 OFF 用を持たない
        assertEquals(on, g.copy(style = GaugeStyle.CHART).forGlow(true).design)
    }

    @Test
    fun imagesAndRenamesCoverBothDesigns() {
        val g = GaugeConfig(pidKey = "RPM", style = GaugeStyle.DIGITAL, design = on, glowOffDesign = off)
        assertEquals(listOf("a.png", "b.png"), g.imageNames())
        val d = Dashboard(id = 1, name = "p", gauges = listOf(g))
        val r = BackupLogic.renameImages(listOf(d), mapOf("b.png" to "a.png")).single().gauges.single()
        assertEquals("a.png", r.glowOffDesign!!.backgroundImage)
        // 発光 OFF 用を持たない形式に変えたら、書き出すときに外す
        assertNull(g.copy(style = GaugeStyle.LAMP).withoutUnusedImages().glowOffDesign)
    }
}
