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
        // 発光 OFF で変わるのは色だけ (画像・フォントは発光 ON のもの)
        assertEquals(on.copy(valueColor = 0xFFFF0000), g.forGlow(true).design)
        assertEquals(on, g.forGlow(false).design)
        val single = g.copy(glowOffDesign = null)
        assertSame(single, single.forGlow(true))
        // チャートなどは発光 OFF 用を持たない
        assertEquals(on, g.copy(style = GaugeStyle.CHART).forGlow(true).design)
    }

    @Test
    fun onlyColorsAreKeptForGlowOff() {
        val g = GaugeConfig(pidKey = "RPM", style = GaugeStyle.DIGITAL, design = on, glowOffDesign = off)
        assertEquals(listOf("a.png"), g.imageNames())
        // 書き出すときは、発光 OFF 用の色以外を発光 ON のものにそろえる
        val clean = g.withoutUnusedImages().glowOffDesign!!
        assertEquals("a.png", clean.backgroundImage)
        assertEquals("f1", clean.valueFont)
        assertEquals(0xFFFF0000, clean.valueColor)
        // 発光 OFF 用を持たない形式に変えたら、書き出すときに外す
        assertNull(g.copy(style = GaugeStyle.LAMP).withoutUnusedImages().glowOffDesign)
    }

    @Test
    fun imageObjectSplitsImageBrightnessAndContrast() {
        val a = GaugeDesign(backgroundImage = "day.png", imageScale = 0.5f, imageBrightness = 0.2f)
        val b = GaugeDesign(backgroundImage = "night.png", imageScale = 2f, imageBrightness = -0.3f, imageContrast = 1.5f)
        val g = GaugeConfig(pidKey = "APP_IMAGE", style = GaugeStyle.IMAGE, design = a, glowOffDesign = b)
        val off = g.forGlow(true).design
        assertEquals("night.png", off.backgroundImage)
        assertEquals(-0.3f, off.imageBrightness, 0f)
        assertEquals(1.5f, off.imageContrast, 0f)
        assertEquals("大きさは共通", 0.5f, off.imageScale, 0f)
        assertEquals(listOf("day.png", "night.png"), g.imageNames())
        // アナログでは画像は分けない
        assertEquals("day.png", g.copy(style = GaugeStyle.ANALOG).forGlow(true).design.backgroundImage)
    }

    @Test
    fun tableSplitsColorsAndValueImage() {
        val tOn = GaugeDesign(valueColor = 0xFFFFFFFF, tableValueBg = 0xFF000000, tableValueImage = "on.png", tableMaxRows = 4)
        val tOff = GaugeDesign(valueColor = 0xFFFF0000, tableValueBg = 0xFF111111, tableValueImage = "off.png", tableMaxRows = 9)
        val g = GaugeConfig(pidKey = "RPM", style = GaugeStyle.TABLE, design = tOn, glowOffDesign = tOff)
        val d = g.forGlow(true).design
        assertEquals(0xFFFF0000, d.valueColor)
        assertEquals(0xFF111111, d.tableValueBg)
        assertEquals("off.png", d.tableValueImage)
        assertEquals("行数などは共通", 4, d.tableMaxRows)
        assertEquals(listOf("on.png", "off.png"), g.imageNames())
    }

    @Test
    fun analogSplitsImageBrightnessAndContrast() {
        val g = GaugeConfig(pidKey = "RPM", style = GaugeStyle.ANALOG, design = GaugeDesign(imageBrightness = 0f, imageContrast = 1f, imageScale = 1.2f),
            glowOffDesign = GaugeDesign(imageBrightness = -0.4f, imageContrast = 0.8f, imageScale = 2f))
        val d = g.forGlow(true).design
        assertEquals(-0.4f, d.imageBrightness, 0f)
        assertEquals(0.8f, d.imageContrast, 0f)
        assertEquals("大きさは共通", 1.2f, d.imageScale, 0f)
    }

    @Test
    fun needleImageIsReferencedOnlyByAnalog() {
        val d = GaugeDesign(needleImage = "needle.png")
        assertEquals(listOf("needle.png"), GaugeConfig(pidKey = "RPM", style = GaugeStyle.ANALOG, design = d).imageNames())
        val bar = GaugeConfig(pidKey = "RPM", style = GaugeStyle.BAR, design = d)
        assertEquals(emptyList<String>(), bar.imageNames())
        assertEquals(null, bar.withoutUnusedImages().design.needleImage)
        val r = BackupLogic.renameImages(listOf(Dashboard(id = 1, name = "p", gauges = listOf(GaugeConfig(pidKey = "RPM", style = GaugeStyle.ANALOG, design = d)))),
            mapOf("needle.png" to "n2.png"))
        assertEquals("n2.png", r.single().gauges.single().design.needleImage)
    }
}
