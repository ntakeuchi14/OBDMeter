package com.obdmeter.ui.gauges

import com.obdmeter.data.ScalePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScaleWarpTest {
    @Test
    fun piecewiseLinearAndInverse() {
        // 0〜8000 で 2000 を 15%、4000 を 60% に置く
        val pr = ScaleWarp.pairs(0.0, 8000.0, listOf(ScalePoint(2000.0, 0.15f), ScalePoint(4000.0, 0.6f)))!!
        assertEquals(0.15f, ScaleWarp.warp(pr, 0.25f), 1e-5f)
        assertEquals(0.375f, ScaleWarp.warp(pr, 0.375f), 1e-5f) // 2000〜4000 の中間 (3000) は 15%〜60% の中間
        assertEquals(1f, ScaleWarp.warp(pr, 1f), 1e-5f)
        for (u in listOf(0f, 0.1f, 0.3f, 0.7f, 0.99f)) assertEquals(u, ScaleWarp.unwarp(pr, ScaleWarp.warp(pr, u)), 1e-4f)
    }

    @Test
    fun invalidOrUniformPointsAreIgnored() {
        assertNull(ScaleWarp.pairs(0.0, 8000.0, null))
        assertNull(ScaleWarp.pairs(0.0, 8000.0, emptyList()))
        assertNull(ScaleWarp.pairs(0.0, 8000.0, listOf(ScalePoint(4000.0, 0.5f))))
        // 位置が戻る点は捨てる
        val pr = ScaleWarp.pairs(0.0, 100.0, listOf(ScalePoint(20.0, 0.5f), ScalePoint(40.0, 0.3f)))!!
        assertEquals(6, pr.size)
    }

    @Test
    fun weightsRoundTrip() {
        val b = ScaleWarp.bounds(0.0, 8000.0, null)
        assertEquals(listOf(0.0, 1000.0, 2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 7000.0, 8000.0), b)
        val w = listOf(0.6f, 0.8f, 2f, 2f, 1.2f, 1f, 0.6f, 0.5f)
        val pts = ScaleWarp.fromWeights(0.0, 8000.0, b, w)
        val back = ScaleWarp.weights(0.0, 8000.0, b, pts)
        // 比率は合計で割り直すので、比 (2000〜3000 ÷ 0〜1000) で比べる
        for (i in w.indices) assertEquals(w[i] / w[0], back[i] / back[0], 1e-3f)
        assertNull(ScaleWarp.fromWeights(0.0, 8000.0, b, List(8) { 1f }))
        // 最大値が切れ目に来ないとき (0〜7500) も最後の区間がある
        assertEquals(7500.0, ScaleWarp.bounds(0.0, 7500.0, null).last(), 0.0)
    }

    @Test
    fun parseAndFormatTypedWeights() {
        assertEquals(listOf(0.6f, 0.8f, 2f, 2f), ScaleWarp.parseWeights("0.6, 0.8,2 、2", 4))
        assertNull("数が合わない", ScaleWarp.parseWeights("1, 2, 3", 4))
        assertNull("0 以下", ScaleWarp.parseWeights("1, 0, 2, 2", 4))
        assertNull("数でない", ScaleWarp.parseWeights("1, a, 2, 2", 4))
        assertEquals("0.6, 1, 2.25", ScaleWarp.formatWeights(listOf(0.6f, 1f, 2.25f)))
    }
}
