package com.obdmeter.ui

import com.obdmeter.data.GaugeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTabsTest {
    @Test
    fun everyStyleHasItemAndPlaceTabs() {
        for (s in GaugeStyle.entries) {
            val tabs = EditorTabs.forStyle(s)
            assertTrue("$s: 最初は項目のタブ", tabs.first() == EditorTab.ITEM || tabs.first() == EditorTab.ITEM_AXIS)
            assertEquals("$s: 最後は配置のタブ", EditorTab.PLACE, tabs.last())
            assertEquals("$s: タブの重複なし", tabs.size, tabs.toSet().size)
        }
    }

    @Test
    fun digitalHasNoNeedleOrScaleTab() {
        val tabs = EditorTabs.forStyle(GaugeStyle.DIGITAL)
        assertFalse(EditorTab.SCALE in tabs)
        assertFalse(EditorTab.BAR in tabs)
        assertFalse(EditorTabs.usesRing(GaugeStyle.DIGITAL))
    }

    @Test
    fun switchingStyleKeepsTabWhenAvailable() {
        assertEquals(EditorTab.LOOK, EditorTabs.keepOrFirst(GaugeStyle.DIGITAL, EditorTab.LOOK))
        assertEquals("針のタブはデジタルに無いので最初のタブ", EditorTab.ITEM, EditorTabs.keepOrFirst(GaugeStyle.DIGITAL, EditorTab.SCALE))
        assertEquals("項目のタブはグラフでは項目・軸", EditorTab.ITEM_AXIS, EditorTabs.keepOrFirst(GaugeStyle.CHART, EditorTab.ITEM))
        assertEquals(EditorTab.PLACE, EditorTabs.keepOrFirst(GaugeStyle.CHART, EditorTab.PLACE))
    }
}
