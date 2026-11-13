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
}
