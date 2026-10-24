package com.obdmeter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceItemsTest {
    @Test
    fun deviceItemsAreInCatalogAndNotBmw() {
        for (d in DeviceItems.ALL) {
            assertEquals(d, Pids.get(d.key))
            assertTrue(d.key, DeviceItems.isDevice(d) && !Pids.isBmwSpecific(d))
        }
    }

    @Test
    fun testSignalsFollowTime() {
        assertEquals(50.0, DeviceItems.testSignal(DeviceItems.SINE, 0)!!, 1e-9)
        assertEquals(100.0, DeviceItems.testSignal(DeviceItems.SINE, 2_500)!!, 1e-9)
        assertEquals(0.0, DeviceItems.testSignal(DeviceItems.SINE, 7_500)!!, 1e-9)
        assertEquals(25.0, DeviceItems.testSignal(DeviceItems.SAW, 12_500)!!, 1e-9)
        assertNull(DeviceItems.testSignal(DeviceItems.PITCH, 0))
    }

    @Test
    fun orientationOfFlatAndTiltedDevice() {
        // 平らに置いて北向き (単位行列)
        val (p0, r0, a0) = DeviceItems.orientation(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        assertEquals(0.0, p0, 1e-6); assertEquals(0.0, r0, 1e-6); assertEquals(0.0, a0, 1e-6)
        // 上端を 30° 持ち上げた (x 軸まわりに +30°)
        val c = Math.cos(Math.toRadians(30.0)).toFloat(); val s = Math.sin(Math.toRadians(30.0)).toFloat()
        val (p1, r1, _) = DeviceItems.orientation(floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c))
        assertEquals("上側を持ち上げるとプラス", 30.0, p1, 1e-4)
        assertEquals(0.0, r1, 1e-4)
    }

    @Test
    fun tiltFromGravityOnly() {
        val (p, r) = DeviceItems.tiltFromGravity(0.0, 0.0, 9.8)
        assertEquals(0.0, p, 1e-9); assertEquals(0.0, r, 1e-9)
        assertEquals(90.0, DeviceItems.tiltFromGravity(0.0, 9.8, 0.0).first, 1e-9)
        assertEquals(-45.0, DeviceItems.tiltFromGravity(9.8, 0.0, 9.8).second, 1e-9)
    }

    @Test
    fun readerNeverQueriesDeviceItems() {
        val p = FakeBmwProtocol()
        val values = hashMapOf(DeviceItems.PITCH to 12.0)
        val r = ObdReader(p).poll(listOf(Pids.get(DeviceItems.PITCH)!!), values)
        assertEquals(0, p.queries.size)
        assertEquals(0, r.ok + r.failed)
        assertEquals("端末の値は消さない", 12.0, values.getValue(DeviceItems.PITCH), 0.0)
    }
}
