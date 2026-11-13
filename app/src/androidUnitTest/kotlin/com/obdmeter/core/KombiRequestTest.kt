package com.obdmeter.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class KombiRequestTest {
    @Test
    fun selfTestEchoesOnlyRoutineId() {
        // 31 23 01 の正常応答は 71 23 (引数は繰り返さない)
        assertArrayEquals(intArrayOf(0x71, 0x23), BmwItems.kombiSelfTestRequest.echo)
        assertEquals("60", BmwItems.kombiSelfTestRequest.canExtAddress)
    }

    @Test
    fun normalRequestEchoesAllPidBytes() {
        assertArrayEquals(intArrayOf(0x70, 0x08, 0x01), PidRequest(0x30, intArrayOf(0x08, 0x01)).echo)
        assertArrayEquals(intArrayOf(0x61, 0x05), BmwItems.kombiSpeedRequest.echo)
    }

    @Test
    fun tachoScaleFollowsJob() {
        // 目盛りの最大 260 km/h (×10 = 2600 = 0x0A28) と換算 5200 (0x1450)
        val d = IntArray(40); d[21] = 0x28; d[22] = 0x0A; d[31] = 0x50; d[32] = 0x14
        val s = BmwItems.kombiTachoScale(d)!!
        assertEquals(260, s.limit)
        assertEquals(2600, s.raw(130.0))
        assertEquals(5200, s.raw(999.0))
        assertArrayEquals(intArrayOf(0x20, 0x06, 0x0A, 0x28), BmwItems.kombiTachoRequest(2600).pid)
    }

    @Test
    fun rpmScaleFollowsJob() {
        // 最大 7000 rpm (0x1B58)、換算 7000
        val d = IntArray(30); d[11] = 0x58; d[12] = 0x1B; d[21] = 0x58; d[22] = 0x1B
        val s = BmwItems.kombiRpmScale(d)!!
        assertEquals(7000, s.limit)
        assertEquals(3500, s.raw(3500.0))
    }
}
