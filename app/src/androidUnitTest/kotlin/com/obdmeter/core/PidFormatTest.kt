package com.obdmeter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PidFormatTest {
    private val def = Pids.get("RPM")!!.copy(decimals = 1)

    @Test
    fun negativeZeroHasNoSign() {
        assertEquals("0.0", def.format(-0.04))
        assertEquals("0.0", def.format(-0.0))
        assertEquals("-0.1", def.format(-0.06))
        assertEquals("1.2", def.format(1.23))
        assertEquals("0", def.copy(decimals = 0).format(-0.4))
        assertEquals("-1", def.copy(decimals = 0).format(-0.6))
    }
}
