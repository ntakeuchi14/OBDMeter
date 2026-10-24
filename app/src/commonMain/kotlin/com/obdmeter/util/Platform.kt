package com.obdmeter.util

/** 現在時刻 (ミリ秒、1970-01-01 UTC から) */
expect fun nowMs(): Long

/** スレッドを [ms] だけ止める (Web では何もしない) */
expect fun sleepMs(ms: Long)

/** 時刻 [ms] の、端末のタイムゾーンでの年・月 (1〜12)・日・時・分・秒・ミリ秒 */
expect fun localParts(ms: Long): IntArray

/** 端末のタイムゾーンの年月日時分秒を時刻 (ミリ秒) にする */
expect fun localToMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long

/** 排他 (Android はロック、Web は単一スレッドなので何もしない) */
expect class PlatformLock() {
    fun lock()
    fun unlock()
}

inline fun <T> PlatformLock.locked(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
