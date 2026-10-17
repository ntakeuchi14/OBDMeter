package com.obdmeter.core

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 車なしで画面確認するための疑似車両。
 * 実際の OBD 応答と同じバイト列を作って返すので、デコード処理もそのまま検証できる。
 * 走行パターン: 加速 → 巡航 → 減速 → 停車 を繰り返す。
 */
class SimulatorProtocol(
    private val clock: () -> Long = System::currentTimeMillis,
    private val responseDelayMs: Long = 15,
) : ObdProtocol {

    private val start = clock()
    private var coolant = 25.0

    /** 実車同様、0x5C (油温) と 0x46 (外気温) は非対応にしておく */
    private val supportedPids = setOf(
        0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
        0x1F, 0x20, 0x2F, 0x33, 0x3C, 0x40, 0x42, 0x49,
    )

    override fun initialize(): String = "シミュレーター (疑似 E90 325i)"

    private data class State(
        val rpm: Double, val speed: Double, val throttle: Double, val map: Double,
    )

    private fun state(): State {
        val t = (clock() - start) / 1000.0
        val phase = t % 40.0
        val speed: Double
        val throttle: Double
        when {
            phase < 12 -> { speed = phase / 12 * 100; throttle = 65 + 25 * sin(t * 1.3) }
            phase < 25 -> { speed = 100 + 4 * sin(t); throttle = 22 + 4 * sin(t * 0.7) }
            phase < 35 -> { speed = 100 * (1 - (phase - 25) / 10); throttle = 0.0 }
            else -> { speed = 0.0; throttle = 0.0 }
        }
        // 6 速 AT 風のギア比で回転数を決める
        val gears = doubleArrayOf(0.0, 25.0, 45.0, 65.0, 85.0, 110.0, 999.0)
        val gear = (1 until gears.size).first { speed < gears[it] }
        val ratio = doubleArrayOf(0.0, 120.0, 70.0, 50.0, 38.0, 30.0, 25.0)[gear]
        val rpm = max(750.0 + 30 * sin(t * 5), speed * ratio + throttle * 15)
        val map = 30 + throttle * 0.75 + 3 * sin(t * 3)
        return State(min(rpm, 7200.0), max(speed, 0.0), throttle.coerceIn(0.0, 100.0), map)
    }

    override fun readPid(pid: Int): IntArray? {
        if (responseDelayMs > 0) Thread.sleep(responseDelayMs)
        val s = state()
        val t = (clock() - start) / 1000.0
        coolant = min(92.0, coolant + 0.05)
        fun u8(v: Double) = v.toInt().coerceIn(0, 255)
        fun u16(v: Double): IntArray { val x = v.toInt().coerceIn(0, 65535); return intArrayOf(x shr 8, x and 0xFF) }
        fun bitmap(base: Int): IntArray {
            val out = IntArray(4)
            for (p in supportedPids) {
                val i = p - base - 1
                if (i in 0 until 32) out[i / 8] = out[i / 8] or (1 shl (7 - i % 8))
            }
            return out
        }
        return when (pid) {
            0x00, 0x20, 0x40, 0x60 -> bitmap(pid).takeIf { pid == 0 || pid in supportedPids }
            0x04 -> intArrayOf(u8((15 + s.throttle * 0.8) * 255 / 100))
            0x05 -> intArrayOf(u8(coolant + 40))
            0x06, 0x08 -> intArrayOf(u8(128 + 4 * sin(t * 2 + pid)))
            0x07, 0x09 -> intArrayOf(u8(128 + 3.0))
            0x0B -> intArrayOf(u8(s.map))
            0x0C -> u16(s.rpm * 4)
            0x0D -> intArrayOf(u8(s.speed))
            0x0E -> intArrayOf(u8((18 + 10 * sin(t) + 64) * 2))
            0x0F -> intArrayOf(u8(32.0 + 40))
            0x10 -> u16((4 + s.rpm / 7000 * s.throttle * 1.6) * 100)
            0x11 -> intArrayOf(u8((14 + s.throttle * 0.86) * 255 / 100))
            0x1F -> u16(t)
            0x2F -> intArrayOf(u8(62.0 * 255 / 100))
            0x33 -> intArrayOf(101)
            0x3C -> u16((450 + s.throttle * 3 + 40) * 10)
            0x42 -> u16(14.1 * 1000 + 100 * sin(t * PI / 10))
            0x49 -> intArrayOf(u8(s.throttle * 255 / 100))
            else -> null
        }
    }

    override fun readAdapterVoltage(): Double = 14.2

    override fun close() {}
}
