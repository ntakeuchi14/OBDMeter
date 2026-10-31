package com.obdmeter.core

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * デモ表示 (シミュレーター) で値を動かす範囲を、メーターで設定した目盛りの範囲に合わせるための表 (項目キー → 最小, 最大)。
 * [com.obdmeter.AppCore] がページの内容から作る
 */
object SimulatorRanges {
    @kotlin.concurrent.Volatile
    var ranges: Map<String, Pair<Double, Double>> = emptyMap()
}

/**
 * 車なしで画面確認するための疑似車両。
 * 実際の OBD 応答と同じバイト列を作って返すので、デコード処理もそのまま検証できる。
 * 走行パターン: 加速 → 巡航 → 減速 → 停車 を繰り返す。
 */
class SimulatorProtocol(
    private val clock: () -> Long = { com.obdmeter.util.nowMs() },
    private val responseDelayMs: Long = 15,
) : ObdProtocol {

    private val start = clock()
    private var coolant = 25.0

    /** 実車同様、0x5C (油温) と 0x46 (外気温) は非対応にしておく */
    private val supportedPids = setOf(
        0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
        0x1F, 0x20, 0x2F, 0x33, 0x3C, 0x40, 0x42, 0x49,
    )

    override fun initAdapter(): String = "シミュレーター"

    override fun connectVehicle(): String = "疑似 E90 335i (標準 PID と BMW 固有の測定値)"

    private data class State(
        val rpm: Double, val speed: Double, val throttle: Double, val map: Double,
        /** ギア段 (停車中は 0) */
        val gear: Int = 0,
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
        return State(min(rpm, 7200.0), max(speed, 0.0), throttle.coerceIn(0.0, 100.0), map, if (speed < 1.0) 0 else gear)
    }

    override fun readPid(pid: Int): IntArray? {
        if (responseDelayMs > 0) com.obdmeter.util.sleepMs(responseDelayMs)
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

    /** BMW の測定値 ID → 項目 (同じ ID の項目が複数あれば最初のもの) */
    private val blockDefs: Map<Int, PidDef> by lazy {
        Pids.ALL.filter { it.kind == PidDef.Kind.BMW_BLOCK }.groupBy { it.blockId }.mapValues { it.value.first() }
    }

    /**
     * BMW の測定値 [id] の生の値。走行パターンに合わせた値を返す (主な項目)。その他は項目の範囲の中でゆっくり変える
     */
    private fun blockRaw(id: Int, size: Int, s: State, t: Double): Long {
        fun phys(v: Double, mul: Double, add: Double = 0.0) = kotlin.math.floor((v - add) / mul + 0.5).toLong()
        return when (id) {
            0x4807 -> phys(s.rpm, 1.0)                                  // 回転数
            0x581F -> phys(coolant, 0.75, -48.0)                         // 水温
            0x460A -> phys(14.1 + 0.1 * sin(t * 0.5), 0.015)             // 電圧
            0x4200 -> phys(32.0 + 2 * sin(t * 0.05), 0.75, -48.0)        // 吸気温
            0x58D5 -> phys(35.0 + s.throttle * 0.3, 1.0, -48.0)          // 吸気温 (過給側)
            0x5A50, 0x5A51 -> phys(if (s.throttle > 60) 0.82 else 1.0 + 0.02 * sin(t * 3 + id), 1 / 1024.0) // λ
            0x5A29 -> phys(s.throttle, 0.390625)                         // アクセル開度
            0x5A25 -> phys((1.2 + s.rpm / 2500.0) * 1000, 1.0)           // 油圧 (bar × 1000)
            0x5A2C -> phys(1013.0 + s.throttle * 10 + 20 * sin(t * 2), 0.0390625) // スロットル前圧力 (ブースト)
            0x4201 -> phys(1013.0, 0.08291752636432648)                   // 大気圧
            0x5881 -> s.gear.toLong()                                    // 計算ギア段
            else -> {
                // 項目の換算式 (1 次式) を逆算して、範囲の 35〜65% あたりで値を動かす
                // (メーターで目盛りの範囲を設定していれば、その範囲の中で動かす)
                val d = blockDefs[id] ?: return (128 + 60 * sin(t * 0.3 + id)).toLong()
                val zero = IntArray(size)
                val one = IntArray(size).also { it[size - 1] = 1 }
                val v0 = runCatching { d.decode(zero) }.getOrNull() ?: return 0
                val a = (runCatching { d.decode(one) }.getOrNull() ?: return 0) - v0
                if (a == 0.0) return 0
                val (lo, hi) = SimulatorRanges.ranges[d.key] ?: (d.min to d.max)
                val target = lo + (hi - lo) * (0.5 + 0.15 * sin(t * 0.25 + id % 17))
                kotlin.math.floor((target - v0) / a + 0.5).toLong()
            }
        }
    }

    /** 生の値を [size] バイト (ビッグエンディアン、負の値は 2 の補数) にする */
    private fun bytesOf(raw: Long, size: Int): List<Int> {
        val bits = 8 * size
        val v = if (bits >= 63) raw else raw and ((1L shl bits) - 1)
        return (size - 1 downTo 0).map { ((v shr (8 * it)) and 0xFF).toInt() }
    }

    /**
     * Mode 01 以外の問い合わせ。
     * - BMW の測定値ブロック (2C で登録 → 21 で読む): 登録した測定値ごとに疑似の値を返す
     * - ステータス (30 xx 01 / 22 xx xx): ゆっくり変わる疑似データ (48 バイト)
     * - そのほかのカスタム PID: PID の値から決まる疑似データ (例: 22 1234 → 12 34 xx yy)
     */
    override fun query(request: PidRequest): IntArray? {
        if (request.mode == 0x01 && request.pid.size == 1) return readPid(request.pid[0])
        if (responseDelayMs > 0) com.obdmeter.util.sleepMs(responseDelayMs)
        val t = (clock() - start) / 1000.0
        if (request.mode == 0x21 && request.setup.isNotEmpty()) {
            val def = request.setup.last()
            if (def.size < 2 || def[0] != 0x2C) return null
            val s = state()
            val out = ArrayList<Int>()
            for (i in 2 until def.size step 6) {
                if (i + 4 >= def.size) break
                val size = def[i + 2]
                val id = (def[i + 3] shl 8) or def[i + 4]
                out += bytesOf(blockRaw(id, size, s, t), size)
            }
            return out.toIntArray()
        }
        if ((request.mode == 0x30 || request.mode == 0x22) && request.header == "7E0") {
            return IntArray(48) { i -> (128 + 60 * sin(t * 0.15 + i * 0.7)).toInt().coerceIn(0, 255) }
        }
        val tick = ((clock() - start) / 100 % 256).toInt()
        val p0 = request.pid.getOrElse(0) { 0 }
        val p1 = request.pid.getOrElse(1) { 0 }
        return intArrayOf(p0, p1, tick, (tick * 7) and 0xFF)
    }

    override fun readAdapterVoltage(): Double = 14.2

    override fun close() {}
}
