package com.obdmeter.util

import java.util.Calendar
import java.util.concurrent.locks.ReentrantLock

actual fun nowMs(): Long = System.currentTimeMillis()

actual fun sleepMs(ms: Long) {
    if (ms > 0) Thread.sleep(ms)
}

actual fun localParts(ms: Long): IntArray {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return intArrayOf(
        c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND),
    )
}

actual fun localToMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
    val c = Calendar.getInstance()
    c.clear()
    c.set(year, month - 1, day, hour, minute, second)
    return c.timeInMillis
}

actual class PlatformLock actual constructor() {
    private val l = ReentrantLock()
    actual fun lock() = l.lock()
    actual fun unlock() = l.unlock()
}
