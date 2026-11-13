package com.obdmeter.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KombiRequestTest {
    @Test
    fun selfTestEchoesOnlyRoutineId() {
        // 31 23 01 の正常応答は 71 23 (引数は繰り返さない)
        assertContentEquals(intArrayOf(0x71, 0x23), BmwItems.kombiSelfTestRequest.echo)
        assertEquals("60", BmwItems.kombiSelfTestRequest.canExtAddress)
    }

    @Test
    fun normalRequestEchoesAllPidBytes() {
        assertContentEquals(intArrayOf(0x70, 0x08, 0x01), PidRequest(0x30, intArrayOf(0x08, 0x01)).echo)
        assertContentEquals(intArrayOf(0x61, 0x05), BmwItems.kombiSpeedRequest.echo)
    }
}
