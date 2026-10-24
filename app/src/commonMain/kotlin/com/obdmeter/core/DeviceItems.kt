package com.obdmeter.core

/**
 * 端末 (Android) のセンサーで測る項目。車につながなくても値が出るので、メーターやグラフの反応を試すのに使う。
 * 値は [com.obdmeter.sensors.DeviceSensors] が出す
 */
object DeviceItems {
    private fun dev(key: String, name: String, short: String, unit: String, min: Double, max: Double, decimals: Int = 0) =
        PidDef(key, "$name (端末)", short, unit, min, max, decimals = decimals, kind = PidDef.Kind.DEVICE)

    const val PITCH = "DEV_PITCH"
    const val ROLL = "DEV_ROLL"
    const val AZIMUTH = "DEV_AZIMUTH"
    const val ACCEL = "DEV_ACCEL"
    const val ACCEL_X = "DEV_ACCEL_X"
    const val ACCEL_Y = "DEV_ACCEL_Y"
    const val ACCEL_Z = "DEV_ACCEL_Z"
    const val MOTION = "DEV_MOTION"
    const val GYRO = "DEV_GYRO"
    const val LIGHT = "DEV_LIGHT"
    const val PROXIMITY = "DEV_PROXIMITY"
    const val PRESSURE = "DEV_PRESSURE"
    const val BATTERY = "DEV_BATTERY"
    const val BATTERY_TEMP = "DEV_BATTERY_TEMP"
    const val BATTERY_VOLT = "DEV_BATTERY_VOLT"
    const val SINE = "DEV_TEST_SINE"
    const val SAW = "DEV_TEST_SAW"

    val ALL: List<PidDef> = listOf(
        dev(PITCH, "傾き 前後", "前後の傾き", "°", -90.0, 90.0),
        dev(ROLL, "傾き 左右", "左右の傾き", "°", -90.0, 90.0),
        dev(AZIMUTH, "方位", "方位", "°", 0.0, 360.0),
        dev(ACCEL, "加速度 (重力込み)", "加速度", "G", 0.0, 3.0, decimals = 2),
        dev(ACCEL_X, "加速度 左右", "加速度 X", "G", -2.0, 2.0, decimals = 2),
        dev(ACCEL_Y, "加速度 上下", "加速度 Y", "G", -2.0, 2.0, decimals = 2),
        dev(ACCEL_Z, "加速度 前後", "加速度 Z", "G", -2.0, 2.0, decimals = 2),
        dev(MOTION, "揺れ (重力を除いた加速度)", "揺れ", "G", 0.0, 2.0, decimals = 2),
        dev(GYRO, "回転の速さ", "回転", "°/s", 0.0, 720.0),
        dev(LIGHT, "明るさ", "明るさ", "lx", 0.0, 1000.0),
        dev(PROXIMITY, "近接センサー", "近接", "cm", 0.0, 10.0, decimals = 1),
        dev(PRESSURE, "気圧", "気圧", "hPa", 950.0, 1050.0, decimals = 1),
        dev(BATTERY, "バッテリー残量", "電池残量", "%", 0.0, 100.0),
        dev(BATTERY_TEMP, "バッテリー温度", "電池温度", "°C", 0.0, 60.0, decimals = 1),
        dev(BATTERY_VOLT, "バッテリー電圧", "電池電圧", "V", 3.0, 4.5, decimals = 2),
        dev(SINE, "テスト信号 サイン波 (10 秒周期)", "サイン波", "", 0.0, 100.0),
        dev(SAW, "テスト信号 のこぎり波 (10 秒周期)", "のこぎり波", "", 0.0, 100.0),
    )

    fun isDevice(d: PidDef): Boolean = d.kind == PidDef.Kind.DEVICE

    /** テスト信号の値 (時刻 [t] ミリ秒、周期 [periodMs]) */
    fun testSignal(key: String, t: Long, periodMs: Long = 10_000L): Double? {
        val phase = (t % periodMs).toDouble() / periodMs
        return when (key) {
            SINE -> 50.0 + 50.0 * kotlin.math.sin(phase * 2 * kotlin.math.PI)
            SAW -> phase * 100.0
            else -> null
        }
    }

    /**
     * 端末の向きを表す回転行列 (3×3、行優先) から 前後・左右の傾きと方位 (度) を求める。
     * Android の getOrientation と同じ式だが、前後の傾きは「画面の上側を持ち上げるとプラス」にそろえる
     * ([tiltFromGravity] と同じ向き)
     */
    fun orientation(r: FloatArray): Triple<Double, Double, Double> {
        val azimuth = deg(kotlin.math.atan2(r[1].toDouble(), r[4].toDouble()))
        val pitch = deg(kotlin.math.asin(r[7].toDouble().coerceIn(-1.0, 1.0)))
        val roll = deg(kotlin.math.atan2((-r[6]).toDouble(), r[8].toDouble()))
        return Triple(pitch, roll, (azimuth + 360.0) % 360.0)
    }

    /**
     * 加速度 (m/s²) だけから求める前後・左右の傾き (度)。向きのセンサーが無い端末用。
     * 前後は画面の上側を持ち上げるとプラス、左右は右側を下げるとプラス
     */
    fun tiltFromGravity(x: Double, y: Double, z: Double): Pair<Double, Double> {
        val pitch = deg(kotlin.math.atan2(y, kotlin.math.sqrt(x * x + z * z)))
        val roll = deg(kotlin.math.atan2(-x, z))
        return pitch to roll
    }

    private fun deg(rad: Double): Double = rad * 180.0 / kotlin.math.PI
}
