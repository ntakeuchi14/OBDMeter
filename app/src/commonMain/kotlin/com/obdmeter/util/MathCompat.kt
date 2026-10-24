package com.obdmeter.util

import kotlin.math.PI
import kotlin.math.floor

/** Java の Math.round と同じ丸め (0.5 は大きいほうへ) */
fun jround(x: Double): Long = floor(x + 0.5).toLong()

fun jround(x: Float): Int = floor(x + 0.5f).toInt()

fun toRadians(deg: Double): Double = deg / 180.0 * PI

fun toDegrees(rad: Double): Double = rad * 180.0 / PI

fun floorMod(a: Long, b: Long): Long = ((a % b) + b) % b
