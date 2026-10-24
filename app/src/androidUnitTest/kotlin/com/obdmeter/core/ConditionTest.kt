package com.obdmeter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionTest {
    @Test fun simpleComparisons() {
        val c = Condition.compile("x >= 100")
        assertTrue(c.test(100.0)); assertTrue(c.test(120.0)); assertFalse(c.test(99.9))
        assertTrue(Condition.compile("x<5").test(4.0))
        assertTrue(Condition.compile("x = 3").test(3.0))
        assertTrue(Condition.compile("x == 3").test(3.0))
        assertTrue(Condition.compile("x != 3").test(2.0))
        assertFalse(Condition.compile("x != 3").test(3.0))
    }

    @Test fun logicAndArithmetic() {
        val band = Condition.compile("x > 90 && x < 110")
        assertTrue(band.test(100.0)); assertFalse(band.test(110.0)); assertFalse(band.test(80.0))
        val out = Condition.compile("x < 11.5 or x > 14.8")
        assertTrue(out.test(11.0)); assertTrue(out.test(15.0)); assertFalse(out.test(13.0))
        assertTrue(Condition.compile("!(x > 3)").test(2.0))
        assertTrue(Condition.compile("not x > 3").test(2.0))
        assertTrue(Condition.compile("abs(x) > 5").test(-6.0))
        assertTrue(Condition.compile("x * 2 + 1 >= 7").test(3.0))
        assertTrue(Condition.compile("max(x, 10) == 10").test(4.0))
        // 比較が無ければ 0 以外で点灯
        assertTrue(Condition.compile("x").test(1.0)); assertFalse(Condition.compile("x").test(0.0))
    }

    @Test fun errors() {
        assertNotNull(Condition.check(""))
        assertNotNull(Condition.check("x >"))
        assertNotNull(Condition.check("y > 3"))
        assertNotNull(Condition.check("x > 3)"))
        assertNotNull(Condition.check("foo(x) > 1"))
        assertNull(Condition.check("x >= 1 && (x < 3 || x > 9)"))
        assertEquals(true, Condition.compile("X >= 1 AND x <= 2").test(1.5))
    }
}
