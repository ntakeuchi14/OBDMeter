package com.obdmeter.util

private fun jsNow(): Double = js("Date.now()")

private fun jsPart(ms: Double, i: Int): Int = js(
    "(function(d){ switch(i){ case 0: return d.getFullYear(); case 1: return d.getMonth()+1; case 2: return d.getDate();" +
        " case 3: return d.getHours(); case 4: return d.getMinutes(); case 5: return d.getSeconds(); default: return d.getMilliseconds(); } })(new Date(ms))"
)

private fun jsLocalToMs(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Double = js("new Date(y, mo - 1, d, h, mi, s).getTime()")

actual fun nowMs(): Long = jsNow().toLong()

actual fun sleepMs(ms: Long) {}

actual fun localParts(ms: Long): IntArray {
    val m = ms.toDouble()
    return IntArray(7) { jsPart(m, it) }
}

actual fun localToMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
    jsLocalToMs(year, month, day, hour, minute, second).toLong()

actual class PlatformLock actual constructor() {
    actual fun lock() {}
    actual fun unlock() {}
}

private fun jsTzOffsetMin(ms: Double): Int = js("new Date(ms).getTimezoneOffset()")

actual fun tzOffsetMs(ms: Long): Int = -jsTzOffsetMin(ms.toDouble()) * 60_000
