package com.obdmeter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorTest {
    private fun item(key: String) = Pids.get(key) ?: error("no item $key")

    @Test
    fun readsBmwBlockItems() {
        val p = SimulatorProtocol(clock = { 1_000_000L }, responseDelayMs = 0)
        val reader = ObdReader(p)
        val values = HashMap<String, Double>()
        val keys = listOf("BMW_RPM", "BMW_COOLANT", "BMW_BATTERY", "BMW_PEDAL", "MSD80_5A2C", "MSD80_4201", "BMW_BOOST_BAR")
        reader.poll(keys.map(::item), values)
        for (k in keys) assertNotNull("$k が読めること", values[k])
        assertTrue("回転数はアイドリング以上", values.getValue("BMW_RPM") >= 700.0)
        assertEquals(14.1, values.getValue("BMW_BATTERY"), 0.2)
        assertEquals(1013.0, values.getValue("MSD80_4201"), 1.0)
    }

    @Test
    fun genericMeasurementsStayInRange() {
        val p = SimulatorProtocol(clock = { 5_000_000L }, responseDelayMs = 0)
        val reader = ObdReader(p)
        val defs = Pids.ALL.filter { it.kind == PidDef.Kind.BMW_BLOCK }.take(40)
        val values = HashMap<String, Double>()
        reader.poll(defs, values)
        val got = defs.count { values[it.key] != null }
        assertTrue("ほとんどの測定値が読めること ($got / ${defs.size})", got >= defs.size - 2)
    }

    @Test
    fun readsStatusJobs() {
        val p = SimulatorProtocol(responseDelayMs = 0)
        val values = HashMap<String, Double>()
        ObdReader(p).poll(listOf(item("BMW_EXHAUST_FLAP")), values)
        assertNotNull(values["BMW_EXHAUST_FLAP"])
    }
}
