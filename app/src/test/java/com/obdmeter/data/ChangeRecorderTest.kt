package com.obdmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeRecorderTest {
    private val t = ReadPlan.RecordTarget("1:2", "MISFIRE")
    private fun HistoryRow.short() = Triple(this.t / 1000, v, cont) to replaceT?.let { it / 1000 }

    @Test
    fun keepsOnlyChangePointsAndOneMovingTail() {
        val r = ChangeRecorder()
        val rows = ArrayList<HistoryRow>()
        // 0 秒に 0、その後 1 秒ごとに 0 が 12 秒続き、13 秒に 1 になる
        rows += r.observe(t, 0.0, 0, 1_000)
        assertEquals("最初の値は途切れの後として書く", listOf(Triple(0L, 0.0, false) to null), rows.map { it.short() })
        rows.clear()
        for (s in 1..12) rows += r.observe(t, 0.0, s * 1_000L, 1_000)
        assertEquals(
            "変わらない間は末尾の行を 5 秒ごとに動かすだけ",
            listOf(Triple(5L, 0.0, true) to null, Triple(10L, 0.0, true) to 5L),
            rows.map { it.short() },
        )
        rows.clear()
        rows += r.observe(t, 1.0, 13_000, 1_000)
        assertEquals(
            "変わったら、前の値を最後に見た時刻 (12 秒) まで書いてから新しい値",
            listOf(Triple(12L, 0.0, true) to 10L, Triple(13L, 1.0, true) to null),
            rows.map { it.short() },
        )
    }

    @Test
    fun interruptionWithChangedValueStartsNewRun() {
        val r = ChangeRecorder()
        r.observe(t, 3.0, 0, 1_000)
        r.observe(t, 3.0, 2_000, 1_000)
        // 1 分見ていなかった (接続が切れていた) 後に値が変わっていた
        val rows = r.observe(t, 4.0, 62_000, 1_000)
        assertEquals(
            listOf(Triple(2L, 3.0, true) to null, Triple(62L, 4.0, false) to null),
            rows.map { it.short() },
        )
    }

    @Test
    fun interruptionWithSameValueJustMovesTail() {
        val r = ChangeRecorder()
        r.observe(t, 3.0, 0, 1_000)
        r.observe(t, 3.0, 6_000, 1_000)  // 末尾の行 (6 秒)
        // 3 時間スリープしていた後も同じ値: 末尾の行を動かすだけ
        val rows = r.observe(t, 3.0, 3 * 3_600_000L, 1_000)
        assertEquals(listOf(Triple(10_800L, 3.0, true) to 6L), rows.map { it.short() })
        assertTrue(r.observe(t, 3.0, 3 * 3_600_000L + 1_000, 1_000).isEmpty())
    }

    @Test
    fun seededFromLastRowContinuesAfterRestart() {
        val r = ChangeRecorder()
        r.seed(t, HistoryRow(t.slot, t.key, 100_000, 3.0, cont = true), lastIsTail = true)
        val rows = r.observe(t, 3.0, 200_000, 1_000)
        assertEquals("起動し直しても同じ値なら末尾を動かすだけ", listOf(Triple(200L, 3.0, true) to 100L), rows.map { it.short() })
    }

    @Test
    fun retainFlushesDroppedTargets() {
        val r = ChangeRecorder()
        r.observe(t, 3.0, 0, 1_000)
        r.observe(t, 3.0, 3_000, 1_000)
        val rows = r.retain(emptySet())
        assertEquals(listOf(Triple(3L, 3.0, true) to null), rows.map { it.short() })
        assertTrue(r.retain(emptySet()).isEmpty())
    }
}
