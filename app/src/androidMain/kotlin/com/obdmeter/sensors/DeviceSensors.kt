package com.obdmeter.sensors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.view.Surface
import android.view.WindowManager
import com.obdmeter.core.DeviceItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 端末のセンサーの値 ([DeviceItems] の項目)。使っている項目のセンサーだけを動かす (電池の消耗を抑える)。
 * 値は [values] に 20 回/秒まで出す
 */
class DeviceSensors(context: Context, private val scope: CoroutineScope) : SensorEventListener, com.obdmeter.DeviceSensorSource {
    private val app = context.applicationContext
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    override val values: StateFlow<Map<String, Double>> = _values

    private val latest = HashMap<String, Double>()
    private var active: Set<String> = emptySet()
    private val registered = HashSet<Int>()
    private var ticker: Job? = null

    /** センサーの種類 → それで出す項目 */
    private val sensorFor: Map<String, Int> = mapOf(
        DeviceItems.PITCH to Sensor.TYPE_ROTATION_VECTOR,
        DeviceItems.ROLL to Sensor.TYPE_ROTATION_VECTOR,
        DeviceItems.AZIMUTH to Sensor.TYPE_ROTATION_VECTOR,
        DeviceItems.ACCEL to Sensor.TYPE_ACCELEROMETER,
        DeviceItems.ACCEL_X to Sensor.TYPE_ACCELEROMETER,
        DeviceItems.ACCEL_Y to Sensor.TYPE_ACCELEROMETER,
        DeviceItems.ACCEL_Z to Sensor.TYPE_ACCELEROMETER,
        DeviceItems.MOTION to Sensor.TYPE_LINEAR_ACCELERATION,
        DeviceItems.GYRO to Sensor.TYPE_GYROSCOPE,
        DeviceItems.LIGHT to Sensor.TYPE_LIGHT,
        DeviceItems.PROXIMITY to Sensor.TYPE_PROXIMITY,
        DeviceItems.PRESSURE to Sensor.TYPE_PRESSURE,
    )

    /** この端末で測れる項目か (センサーが無い項目は「非対応」) */
    fun isAvailable(key: String): Boolean {
        val type = sensorFor[key] ?: return true // 電池・テスト信号はどの端末でも出る
        if (sm.getDefaultSensor(type) != null) return true
        // 向きのセンサーが無ければ、傾きは加速度から求める (方位は出せない)
        return (key == DeviceItems.PITCH || key == DeviceItems.ROLL) && sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
            key == DeviceItems.MOTION && sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    /** 使っている項目を伝える。空にすると全部止める */
    @Synchronized
    override fun setActive(keys: Set<String>) {
        val want = keys.filter { it.startsWith("DEV_") }.toSet()
        if (want == active) return
        active = want
        val types = want.mapNotNull { k -> sensorFor[k]?.let { resolveType(k, it) } }.toSet()
        // 不要になったセンサーを止め、必要なセンサーを動かす
        for (t in registered.toList()) if (t !in types) {
            sm.getDefaultSensor(t)?.let { sm.unregisterListener(this, it) }
            registered -= t
        }
        for (t in types) if (t !in registered) {
            val s = sm.getDefaultSensor(t) ?: continue
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
            registered += t
        }
        synchronized(latest) { latest.keys.retainAll(want) }
        if (want.isEmpty()) {
            ticker?.cancel(); ticker = null
            _values.value = emptyMap()
        } else if (ticker == null) {
            ticker = scope.launch {
                var lastBattery = 0L
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val keysNow = active
                    if (keysNow.any { it.startsWith("DEV_BATTERY") } && now - lastBattery >= 5_000) {
                        readBattery(); lastBattery = now
                    }
                    for (k in keysNow) DeviceItems.testSignal(k, now)?.let { put(k, it) }
                    _values.value = synchronized(latest) { HashMap(latest) }
                    delay(50)
                }
            }
        }
    }

    /** センサーが無いときの代わり (向き → 加速度、揺れ → 加速度) */
    private fun resolveType(key: String, type: Int): Int =
        if (sm.getDefaultSensor(type) != null) type
        else if (type == Sensor.TYPE_ROTATION_VECTOR || type == Sensor.TYPE_LINEAR_ACCELERATION) Sensor.TYPE_ACCELEROMETER
        else type

    private fun put(key: String, v: Double) {
        if (key in active) synchronized(latest) { latest[key] = v }
    }

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val gravity = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                remapForScreen(rot, remapped)
                val (pitch, roll, az) = DeviceItems.orientation(remapped)
                put(DeviceItems.PITCH, pitch); put(DeviceItems.ROLL, roll); put(DeviceItems.AZIMUTH, az)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = screenAxes(e.values[0], e.values[1], e.values[2])
                put(DeviceItems.ACCEL_X, x / G); put(DeviceItems.ACCEL_Y, y / G); put(DeviceItems.ACCEL_Z, z / G)
                put(DeviceItems.ACCEL, sqrt(x * x + y * y + z * z) / G)
                // 重力の向きをゆっくり追いかけ、差を揺れとする (揺れのセンサーが無い端末用)
                for (i in 0..2) gravity[i] = gravity[i] * 0.9f + e.values[i] * 0.1f
                if (sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) == null) {
                    val dx = e.values[0] - gravity[0]; val dy = e.values[1] - gravity[1]; val dz = e.values[2] - gravity[2]
                    put(DeviceItems.MOTION, sqrt((dx * dx + dy * dy + dz * dz).toDouble()) / G)
                }
                if (sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null) {
                    val (pitch, roll) = DeviceItems.tiltFromGravity(x, y, z)
                    put(DeviceItems.PITCH, pitch); put(DeviceItems.ROLL, roll)
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
                put(DeviceItems.MOTION, sqrt(x * x + y * y + z * z) / G)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
                put(DeviceItems.GYRO, Math.toDegrees(sqrt(x * x + y * y + z * z)))
            }
            Sensor.TYPE_LIGHT -> put(DeviceItems.LIGHT, e.values[0].toDouble())
            Sensor.TYPE_PROXIMITY -> put(DeviceItems.PROXIMITY, e.values[0].toDouble())
            Sensor.TYPE_PRESSURE -> put(DeviceItems.PRESSURE, e.values[0].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** 画面の向き (横向きなど) に合わせて座標を回す。傾きが画面に対しての向きになる */
    @Suppress("DEPRECATION")
    private fun rotation(): Int =
        runCatching { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_0)

    private fun remapForScreen(r: FloatArray, out: FloatArray) {
        val (ax, ay) = when (rotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(r, ax, ay, out)) r.copyInto(out)
    }

    private fun screenAxes(x: Float, y: Float, z: Float): Triple<Double, Double, Double> = when (rotation()) {
        Surface.ROTATION_90 -> Triple(-y.toDouble(), x.toDouble(), z.toDouble())
        Surface.ROTATION_180 -> Triple(-x.toDouble(), -y.toDouble(), z.toDouble())
        Surface.ROTATION_270 -> Triple(y.toDouble(), -x.toDouble(), z.toDouble())
        else -> Triple(x.toDouble(), y.toDouble(), z.toDouble())
    }

    private fun readBattery() {
        val i: Intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) put(DeviceItems.BATTERY, level * 100.0 / scale)
        i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            ?.let { put(DeviceItems.BATTERY_TEMP, it / 10.0) }
        i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }
            ?.let { put(DeviceItems.BATTERY_VOLT, if (it > 100) it / 1000.0 else it.toDouble()) }
    }

    private companion object {
        const val G = 9.80665
    }
}
