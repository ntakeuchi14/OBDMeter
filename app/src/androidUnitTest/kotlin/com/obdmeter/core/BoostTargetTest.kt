package com.obdmeter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BoostTargetTest {
    @Test
    fun targetBoostIsTargetMinusBaro() {
        val bar = Pids.get("BMW_BOOST_TARGET_BAR")
        val psi = Pids.get("BMW_BOOST_TARGET_PSI")
        assertNotNull(bar); assertNotNull(psi)
        val v = mapOf("MSD80_5AB0" to 2013.0, "MSD80_4201" to 1013.0)
        assertEquals(1.0, bar!!.derive!!(v)!!, 1e-9)
        assertEquals(14.503774, psi!!.derive!!(v)!!, 1e-6)
        assertEquals(Pids.ReadKind.BLOCK, Pids.readKind(bar))
    }
}
