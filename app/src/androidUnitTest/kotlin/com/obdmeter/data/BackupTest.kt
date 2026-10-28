package com.obdmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val oilTemp = CustomPid(id = 100, name = "油温", unit = "°C", mode = "22", pid = "4402", formula = "A*0.75-48")
    private val boost = CustomPid(id = 200, name = "ブースト目標", unit = "kPa", mode = "22", pid = "4205", formula = "(A*256+B)/10")

    private fun page(id: Long, name: String, vararg gauges: GaugeConfig) = Dashboard(id = id, name = name, gauges = gauges.toList())

    private val designed = GaugeConfig(
        pidKey = oilTemp.key, style = GaugeStyle.ANALOG, id = 1, x = 0, y = 0, w = 12, h = 12,
        design = GaugeDesign(
            color = 0xFFFF9800, startAngle = -135f, endAngle = 135f, ring = RingStyle.METAL,
            redZoneFrom = 120.0, minorDivisions = 4, backgroundImage = "bg_abc.png", tickRadius = 0.8f,
        ),
    )

    @Test
    fun roundTripKeepsEveryDesignField() {
        val m = BackupManifest(dashboards = listOf(page(1, "メイン", designed)), customPids = listOf(oilTemp))
        val back = BackupLogic.decode(BackupLogic.encode(m))
        assertEquals(m, back)
        assertEquals(setOf("bg_abc.png"), BackupLogic.referencedImages(back.dashboards))
    }

    @Test
    fun imagesNotShownByCurrentStyleAreUnused() {
        val table = designed.copy(id = 2, style = GaugeStyle.TABLE,
            design = designed.design.copy(backgroundImage = "bg_old.png", tableValueImage = "bg_cell.png"))
        val chart = designed.copy(id = 3, style = GaugeStyle.CHART,
            design = designed.design.copy(backgroundImage = "bg_old2.png"))
        val p = page(1, "メイン", designed, table, chart)
        assertEquals(setOf("bg_abc.png", "bg_cell.png"), BackupLogic.referencedImages(listOf(p)))
        val cleaned = p.withoutUnusedImages()
        assertEquals("bg_abc.png", cleaned.gauges[0].design.backgroundImage)
        assertNull(cleaned.gauges[1].design.backgroundImage)
        assertEquals("bg_cell.png", cleaned.gauges[1].design.tableValueImage)
        assertNull(cleaned.gauges[2].design.backgroundImage)
        assertTrue(page(2, "x", designed).let { it.withoutUnusedImages() === it })
    }

    @Test
    fun rejectsOtherFiles() {
        for (text in listOf("{}", """{"format":"other"}""", "not json", """{"format":"obdmeter-backup","version":99}""")) {
            try {
                BackupLogic.decode(text)
                throw AssertionError("should reject: $text")
            } catch (e: IllegalArgumentException) { /* ok */ }
        }
    }

    @Test
    fun ignoresUnknownFieldsFromNewerMinorChanges() {
        val text = """{"format":"obdmeter-backup","version":1,"futureField":true,
            "dashboards":[{"id":5,"name":"P","gauges":[{"pidKey":"RPM","design":{"futureLook":1}}]}]}"""
        val m = BackupLogic.decode(text)
        assertEquals("RPM", m.dashboards.single().gauges.single().pidKey)
    }

    @Test
    fun replaceUsesIncomingAndRenamesImages() {
        val incoming = BackupManifest(dashboards = listOf(page(1, "メイン", designed)), customPids = listOf(oilTemp))
        val r = BackupLogic.apply(
            current = listOf(page(9, "古いページ")), currentPids = listOf(boost),
            incoming = incoming, imageRename = mapOf("bg_abc.png" to "bg_new.png"),
            mode = ImportMode.REPLACE, newId = { 0 },
        )
        assertEquals(listOf("メイン"), r.dashboards.map { it.name })
        assertEquals(listOf(oilTemp), r.customPids)
        val g = r.dashboards.single().gauges.single()
        assertEquals("bg_new.png", g.design.backgroundImage)
        assertEquals(RingStyle.METAL, g.design.ring)
        assertEquals(-135f, g.design.startAngle)
    }

    @Test
    fun pageBackgroundRoundTripsAndImageIsRenamed() {
        val bg = PageBackground(color = 0xFF101820, pattern = BgPattern.CARBON, patternAlpha = 0.5f, image = "bg_page.png", imageWidth = 0.6f)
        val incoming = BackupManifest(dashboards = listOf(page(1, "メイン", designed).copy(background = bg)))
        val r = BackupLogic.apply(emptyList(), emptyList(), incoming, mapOf("bg_page.png" to "bg_p2.png"), ImportMode.MERGE, newId = { 5 })
        val got = r.dashboards.single().background
        assertEquals(bg.copy(image = "bg_p2.png"), got)
        assertEquals(setOf("bg_abc.png", "bg_page.png"), BackupLogic.referencedImages(incoming.dashboards))
    }

    @Test
    fun replaceDropsImagesThatWereNotInTheFile() {
        val incoming = BackupManifest(dashboards = listOf(page(1, "メイン", designed)))
        val r = BackupLogic.apply(emptyList(), emptyList(), incoming, emptyMap(), ImportMode.REPLACE, newId = { 0 })
        assertNull(r.dashboards.single().gauges.single().design.backgroundImage)
    }

    @Test
    fun mergeRemapsIdsNamesAndCustomPidKeys() {
        // 端末側にも id 100 のカスタム PID があるが中身は別物 → 取り込む方は新しい id になる
        val localConflict = boost.copy(id = 100)
        var seq = 1000L
        val incoming = BackupManifest(
            dashboards = listOf(page(1, "メイン", designed)),
            customPids = listOf(oilTemp),
        )
        val r = BackupLogic.apply(
            current = listOf(page(1, "メイン")), currentPids = listOf(localConflict),
            incoming = incoming, imageRename = mapOf("bg_abc.png" to "bg_new.png"),
            mode = ImportMode.MERGE, newId = { seq++ },
        )
        assertEquals(listOf("メイン", "メイン (2)"), r.dashboards.map { it.name })
        assertEquals(2, r.dashboards.map { it.id }.toSet().size)
        assertEquals(2, r.customPids.size)
        val imported = r.customPids.single { it.name == "油温" }
        assertTrue(imported.id != 100L)
        val g = r.dashboards.last().gauges.single()
        assertEquals(imported.key, g.pidKey)
        assertEquals("bg_new.png", g.design.backgroundImage)
    }

    @Test
    fun mergeRemapsFallbackKeys() {
        val g = GaugeConfig(pidKey = "COOLANT", id = 1, x = 0, y = 0, w = 6, h = 6, fallbackKey = oilTemp.key)
        var seq = 5000L
        val r = BackupLogic.apply(
            current = emptyList(), currentPids = listOf(boost.copy(id = 100)),
            incoming = BackupManifest(dashboards = listOf(page(1, "P", g)), customPids = listOf(oilTemp)),
            imageRename = emptyMap(), mode = ImportMode.MERGE, newId = { seq++ },
        )
        val imported = r.customPids.single { it.name == "油温" }
        assertEquals(imported.key, r.dashboards.single().gauges.single().fallbackKey)
    }

    @Test
    fun mergeReusesIdenticalCustomPid() {
        val incoming = BackupManifest(
            dashboards = listOf(page(1, "追加ページ", designed)),
            customPids = listOf(oilTemp.copy(id = 555)),
        )
        val r = BackupLogic.apply(
            current = emptyList(), currentPids = listOf(oilTemp),
            incoming = incoming, imageRename = emptyMap(), mode = ImportMode.MERGE, newId = { 7 },
        )
        assertEquals(listOf(oilTemp), r.customPids)
        assertEquals(oilTemp.key, r.dashboards.single().gauges.single().pidKey)
    }

    @Test
    fun fontReferencesAreRemappedOrDropped() {
        val font = FontEntry(id = "font_old1", name = "Digital", file = "font_old1.ttf", sha = "abc")
        val g = designed.copy(design = designed.design.copy(valueFont = "font_old1", labelFont = "font_missing"))
        val m = BackupManifest(dashboards = listOf(page(1, "P", g)), customPids = listOf(oilTemp), fonts = listOf(font))
        // マニフェストにフォントが残ること
        assertEquals(listOf(font), BackupLogic.decode(BackupLogic.encode(m)).fonts)
        val r = BackupLogic.apply(
            current = emptyList(), currentPids = emptyList(), incoming = m,
            imageRename = emptyMap(), mode = ImportMode.REPLACE, newId = { 0 },
            fontRename = mapOf("font_old1" to "font_new1"),
        )
        val d = r.dashboards.single().gauges.single().design
        assertEquals("font_new1", d.valueFont)
        assertNull(d.labelFont) // ファイルに含まれていないフォントは既定に戻す
    }

    @Test
    fun safeFontNames() {
        assertTrue(BackupLogic.SAFE_FONT_NAME.matches("font_1a2b3c4d5e6f.ttf"))
        assertTrue(BackupLogic.SAFE_FONT_NAME.matches("font_x.otf"))
        for (bad in listOf("../font.ttf", "a/b.ttf", "font.woff", "font.ttf.exe")) {
            assertTrue("should reject $bad", !BackupLogic.SAFE_FONT_NAME.matches(bad))
        }
    }

    @Test
    fun safeImageNames() {
        assertTrue(BackupLogic.SAFE_IMAGE_NAME.matches("bg_1a2b3c.png"))
        for (bad in listOf("../x.png", "a/b.png", "x.jpg", "", "..png/..")) {
            assertTrue("should reject $bad", !BackupLogic.SAFE_IMAGE_NAME.matches(bad))
        }
    }

    @Test
    fun pollIntervalsReplaceOrKeepCurrent() {
        val withIv = BackupManifest(dashboards = listOf(page(1, "P")), pollIntervalsMs = mapOf("COOLANT" to 5_000))
        val r = BackupLogic.apply(emptyList(), emptyList(), withIv, emptyMap(), ImportMode.REPLACE, newId = { 0 },
            currentIntervals = mapOf("RPM" to 500))
        assertEquals(mapOf("COOLANT" to 5_000), r.pollIntervalsMs)
        val old = BackupManifest(dashboards = listOf(page(1, "P")))
        val r2 = BackupLogic.apply(emptyList(), emptyList(), old, emptyMap(), ImportMode.REPLACE, newId = { 0 },
            currentIntervals = mapOf("RPM" to 500))
        assertEquals("古いファイル (取得周期なし) は今の設定を残す", mapOf("RPM" to 500), r2.pollIntervalsMs)
    }

    @Test
    fun mergeAddsIntervalsAndRenamesCustomKeysEverywhere() {
        val chart = GaugeConfig(
            pidKey = "RPM", style = GaugeStyle.CHART, id = 1, chartKeys = listOf(oilTemp.key),
            itemLabels = mapOf(oilTemp.key to "油温"), chartAxis = mapOf(oilTemp.key to false),
        )
        val incoming = BackupManifest(
            dashboards = listOf(page(1, "グラフ", chart)), customPids = listOf(oilTemp),
            pollIntervalsMs = mapOf(oilTemp.key to 5_000, "RPM" to 1_000),
        )
        val r = BackupLogic.apply(
            current = emptyList(), currentPids = listOf(boost), incoming = incoming, imageRename = emptyMap(),
            mode = ImportMode.MERGE, newId = { 300 }, currentIntervals = mapOf("RPM" to 200),
        )
        val newKey = r.customPids.single { it.id == 300L }.key
        val g = r.dashboards.single().gauges.single()
        assertEquals(listOf(newKey), g.chartKeys)
        assertEquals(mapOf(newKey to "油温"), g.itemLabels)
        assertEquals(mapOf(newKey to false), g.chartAxis)
        assertEquals("今の設定が優先、ファイルにだけある項目を追加", mapOf("RPM" to 200, newKey to 5_000), r.pollIntervalsMs)
    }
}
