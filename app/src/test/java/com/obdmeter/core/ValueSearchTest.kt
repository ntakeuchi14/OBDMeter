package com.obdmeter.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueSearchTest {
    @Test fun assembleMultiFrame() {
        // 実車のログ (22 4003)
        val lines = listOf("612F110186240030000", "612F121000000000000", "612F122000000000101", "612F123A601A4FFFFFF", "612F124FFFFFFFFFFFF")
        val d = ValueSearch.assemble(lines)!!
        assertTrue(d.size == 0x18)
        assertArrayEquals(intArrayOf(0x62, 0x40, 0x03), d.copyOfRange(0, 3))
        // 単一フレーム
        assertArrayEquals(intArrayOf(0x62, 0x40, 0x01, 0x57), ValueSearch.assemble(listOf("612F10462400157")))
        assertArrayEquals(intArrayOf(0x7F, 0x22, 0x12), ValueSearch.assemble(listOf("612F1037F2212")))
    }

    @Test fun findsCountersInAnyOrder() {
        val targets = listOf(14L, 50L, 27L, 27L, 71L, 37L)
        // 2 バイト (ビッグエンディアン)、点火順 (1-5-3-6-2-4) で並んでいる場合
        val data = intArrayOf(0x62, 0x5A, 0x01, 0x00, 0x0E, 0x00, 0x47, 0x00, 0x1B, 0x00, 0x25, 0x00, 0x32, 0x00, 0x1B, 0xFF)
        val r = ValueSearch.match(data, targets)
        assertNotNull(r)
        assertTrue(r!!.contains("一致 6/6"))
        // 1 バイトずつの場合
        assertTrue(ValueSearch.match(intArrayOf(0, 14, 50, 27, 27, 71, 37, 0), targets)!!.contains("一致 6/6"))
        // 無関係なデータ
        assertNull(ValueSearch.match(intArrayOf(0, 0, 0, 0, 1, 1, 0xA6, 1, 0xA4), targets))
    }
}
