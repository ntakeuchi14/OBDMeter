package com.obdmeter.ui.gauges

import com.obdmeter.core.PidDef
import com.obdmeter.data.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartMathTest {
    // 記録のある区間: 0〜100 と 1000〜1100 (間の 900 は記録なし)、詰めた幅は 10
    private val axis = TimeAxis(listOf(0L to 100L, 1_000L to 1_100L), pad = 10)

    @Test
    fun gapIsCollapsedToPad() {
        assertEquals(50L, axis.toC(50))
        assertEquals(100L, axis.toC(100))
        assertEquals("区間の間は pad の幅に詰める", 110L, axis.toC(500))
        assertEquals(110L, axis.toC(1_000))
        assertEquals(160L, axis.toC(1_050))
        assertEquals("最後の区間より後はそのまま伸びる", 310L, axis.toC(1_200))
        assertEquals("最初の区間より前もそのまま", -50L, axis.toC(-50))
    }

    @Test
    fun realTimeRoundTripsInsideSegments() {
        for (t in listOf(0L, 30L, 100L, 1_000L, 1_099L, 1_500L)) assertEquals(t, axis.toReal(axis.toC(t)))
        assertEquals(-20L, axis.toReal(-20))
    }

    @Test
    fun emptyAxisIsIdentity() {
        val id = TimeAxis(emptyList(), 10)
        assertEquals(12345L, id.toC(12345))
        assertEquals(12345L, id.toReal(12345))
        assertEquals(listOf(5L to 9L), id.overlaps(5, 9))
    }

    @Test
    fun overlapsReturnsClippedSegments() {
        assertEquals(listOf(50L to 100L, 1_000L to 1_050L), axis.overlaps(50, 1_050))
        assertEquals("最後の区間は表示の右端まで伸ばす", listOf(1_000L to 1_300L), axis.overlaps(900, 1_300))
        assertEquals(emptyList<Pair<Long, Long>>(), axis.overlaps(200, 900))
    }

    private fun def(key: String, min: Double, max: Double) = PidDef(key, key, key, "", min, max)

    @Test
    fun axesCombineRangesPerSide() {
        val a = ChartAxes.of(listOf(
            ChartSeries(def("a", 0.0, 100.0), null, right = true),
            ChartSeries(def("b", -10.0, 50.0), null, right = true),
            ChartSeries(def("c", 10.0, 20.0), null, right = false),
        ))
        assertEquals(-10.0 to 100.0, a.right)
        assertEquals(10.0 to 20.0, a.left)
        assertNull(ChartAxes.of(listOf(ChartSeries(def("a", 0.0, 1.0), null))).left)
    }

    @Test
    fun cursorSnapsToNearestRecordAndReadsValues() {
        val data = mapOf(
            "rpm" to listOf(Sample(1_000, 800f), Sample(2_000, 3000f)),
            "temp" to listOf(Sample(1_900, 85f)),
        )
        assertEquals(1_900L, snapCursor(data, 1_870))
        assertEquals("記録が無ければそのまま", 5L, snapCursor(emptyMap(), 5))
        val v = valuesAt(data, listOf("rpm", "temp", "none"), 2_000, span = 60_000)
        assertEquals(3000.0, v["rpm"]!!, 0.0)
        assertEquals(85.0, v["temp"]!!, 0.0)
        assertNull(v["none"])
        assertNull("1 画面の 1/20 より離れた記録は出さない", valuesAt(data, listOf("temp"), 9_000, span = 60_000)["temp"])
    }

    @Test
    fun tickStepKeepsAboutFiveTicksPerScreen() {
        // 間隔 × 5 が 1 画面の期間以上になる一番小さい候補
        assertEquals(60_000L, timeStep(60_000))
        assertEquals(900_000L, timeStep(3_600_000))
        assertEquals(600_000L, timeStep(3_000_000))
        assertEquals(TIME_STEPS.last(), timeStep(Long.MAX_VALUE / 10))
    }

    @Test
    fun gapThresholdUsesLargestRule() {
        assertEquals(5_000L, gapThreshold(1_000, 60_000))
        assertEquals(180_000L, gapThreshold(60_000, 3_600_000))
        assertEquals(432_000L, gapThreshold(1_000, 86_400_000))
    }

    @Test
    fun stepValuesHoldPreviousRecord() {
        val data = mapOf("A" to listOf(Sample(1_000, 1f), Sample(10_000, 2f)))
        assertEquals(1.0, valuesAtStep(data, listOf("A"), 9_000)["A"]!!, 1e-9)
        assertEquals(2.0, valuesAtStep(data, listOf("A"), 50_000)["A"]!!, 1e-9)
        assertEquals(null, valuesAtStep(data, listOf("A"), 500)["A"])
    }
}
