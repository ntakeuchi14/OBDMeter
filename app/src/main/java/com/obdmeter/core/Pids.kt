package com.obdmeter.core

/**
 * メーターに表示できる項目の定義。
 * - MODE01: OBD2 Mode 01 の PID を読んで [decode] で物理値に変換
 * - ADAPTER_VOLTAGE: ELM327 の ATRV (アダプター側で測るバッテリー電圧)
 * - DERIVED: 他の項目から計算 (ブースト圧、燃料消費量など)
 */
data class PidDef(
    val key: String,
    val name: String,
    val shortName: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int = 0,
    val warnAbove: Double? = null,
    val warnBelow: Double? = null,
    val kind: Kind = Kind.MODE01,
    val pid: Int = -1,
    val minBytes: Int = 1,
    /** 変化が遅い項目は数サイクルに 1 回だけ読む (更新速度を稼ぐため)。 */
    val slow: Boolean = false,
    val decode: (IntArray) -> Double = { 0.0 },
    val deps: List<String> = emptyList(),
    val derive: (Map<String, Double>) -> Double? = { null },
) {
    enum class Kind { MODE01, ADAPTER_VOLTAGE, DERIVED }

    fun format(value: Double?): String =
        if (value == null) "---" else "%.${decimals}f".format(value)
}

object Pids {
    private fun a(d: IntArray) = d[0].toDouble()
    private fun ab(d: IntArray) = (d[0] * 256 + d[1]).toDouble()

    val ALL: List<PidDef> = listOf(
        PidDef("RPM", "エンジン回転数", "回転数", "rpm", 0.0, 8000.0, warnAbove = 7000.0,
            pid = 0x0C, minBytes = 2, decode = { ab(it) / 4.0 }),
        PidDef("SPEED", "車速", "車速", "km/h", 0.0, 260.0, pid = 0x0D, decode = ::a),
        PidDef("COOLANT", "冷却水温", "水温", "°C", 40.0, 130.0, warnAbove = 110.0,
            pid = 0x05, slow = true, decode = { a(it) - 40 }),
        PidDef("IAT", "吸気温度", "吸気温", "°C", -20.0, 80.0, warnAbove = 60.0,
            pid = 0x0F, slow = true, decode = { a(it) - 40 }),
        PidDef("OIL_TEMP", "油温", "油温", "°C", 40.0, 150.0, warnAbove = 130.0,
            pid = 0x5C, slow = true, decode = { a(it) - 40 }),
        PidDef("LOAD", "エンジン負荷", "負荷", "%", 0.0, 100.0, pid = 0x04, decode = { a(it) * 100 / 255 }),
        PidDef("THROTTLE", "スロットル開度", "スロットル", "%", 0.0, 100.0,
            pid = 0x11, decode = { a(it) * 100 / 255 }),
        PidDef("PEDAL", "アクセル開度", "アクセル", "%", 0.0, 100.0,
            pid = 0x49, decode = { a(it) * 100 / 255 }),
        PidDef("MAP", "吸気管圧力", "MAP", "kPa", 0.0, 255.0, pid = 0x0B, decode = ::a),
        PidDef("BARO", "大気圧", "大気圧", "kPa", 70.0, 110.0, pid = 0x33, slow = true, decode = ::a),
        PidDef("MAF", "吸入空気量", "MAF", "g/s", 0.0, 250.0, decimals = 1,
            pid = 0x10, minBytes = 2, decode = { ab(it) / 100 }),
        PidDef("TIMING", "点火時期", "点火時期", "°", -20.0, 60.0, decimals = 1,
            pid = 0x0E, decode = { a(it) / 2 - 64 }),
        PidDef("STFT1", "短期燃調 B1", "STFT1", "%", -25.0, 25.0, decimals = 1,
            pid = 0x06, decode = { (a(it) - 128) * 100 / 128 }),
        PidDef("LTFT1", "長期燃調 B1", "LTFT1", "%", -25.0, 25.0, decimals = 1,
            pid = 0x07, slow = true, decode = { (a(it) - 128) * 100 / 128 }),
        PidDef("STFT2", "短期燃調 B2", "STFT2", "%", -25.0, 25.0, decimals = 1,
            pid = 0x08, decode = { (a(it) - 128) * 100 / 128 }),
        PidDef("LTFT2", "長期燃調 B2", "LTFT2", "%", -25.0, 25.0, decimals = 1,
            pid = 0x09, slow = true, decode = { (a(it) - 128) * 100 / 128 }),
        PidDef("FUEL_LEVEL", "燃料残量", "燃料", "%", 0.0, 100.0, warnBelow = 12.0,
            pid = 0x2F, slow = true, decode = { a(it) * 100 / 255 }),
        PidDef("CAT_TEMP", "触媒温度 B1S1", "触媒温", "°C", 0.0, 1000.0, warnAbove = 900.0,
            pid = 0x3C, minBytes = 2, slow = true, decode = { ab(it) / 10 - 40 }),
        PidDef("AMBIENT", "外気温", "外気温", "°C", -20.0, 50.0,
            pid = 0x46, slow = true, decode = { a(it) - 40 }),
        PidDef("ECU_VOLT", "ECU 電圧", "ECU電圧", "V", 10.0, 16.0, decimals = 1, warnBelow = 12.0,
            pid = 0x42, minBytes = 2, slow = true, decode = { ab(it) / 1000 }),
        PidDef("RUNTIME", "始動後経過時間", "経過時間", "s", 0.0, 3600.0,
            pid = 0x1F, minBytes = 2, slow = true, decode = ::ab),
        PidDef("BATTERY", "バッテリー電圧 (アダプター)", "バッテリー", "V", 10.0, 16.0, decimals = 1,
            warnBelow = 12.0, kind = PidDef.Kind.ADAPTER_VOLTAGE, slow = true),
        PidDef("BOOST", "ブースト圧 (MAP−大気圧)", "ブースト", "bar", -1.0, 1.5, decimals = 2,
            kind = PidDef.Kind.DERIVED, deps = listOf("MAP", "BARO"),
            derive = { v -> val m = v["MAP"]; val b = v["BARO"] ?: 101.3; m?.let { (it - b) / 100 } }),
        PidDef("FUEL_RATE", "燃料消費量 (MAF 推定)", "燃費量", "L/h", 0.0, 40.0, decimals = 1,
            kind = PidDef.Kind.DERIVED, deps = listOf("MAF"),
            // ガソリン: 空燃比 14.7、密度 745 g/L
            derive = { v -> v["MAF"]?.let { it * 3600 / 14.7 / 745 } }),
        PidDef("ECONOMY", "瞬間燃費 (MAF 推定)", "瞬間燃費", "km/L", 0.0, 30.0, decimals = 1,
            kind = PidDef.Kind.DERIVED, deps = listOf("MAF", "SPEED"),
            derive = { v ->
                val maf = v["MAF"]; val spd = v["SPEED"]
                if (maf == null || spd == null || maf < 0.1) null
                else (spd / (maf * 3600 / 14.7 / 745)).coerceAtMost(99.9)
            }),
    )

    private val byKey = ALL.associateBy { it.key }

    fun get(key: String): PidDef? = byKey[key]
}

/**
 * プロトコルを使って、表示中の項目を 1 サイクル分読み出す。
 * 対応していない PID はスキップし、連続失敗した PID は一時的に間引く。
 */
class ObdReader(
    private val protocol: ObdProtocol,
    private val logger: CommLogger = CommLogger.NONE,
) {
    /** 車両が対応している Mode 01 PID。取得できなかった場合は null (= 全部試す)。 */
    var supported: Set<Int>? = null
        private set

    private val failStreak = mutableMapOf<String, Int>()
    private var cycle = 0L
    private var adapterVoltageSupported = true

    fun detectSupported() {
        supported = runCatching { protocol.readSupportedPids() }
            .onFailure { logger.log(CommLogger.Direction.ERROR, "対応 PID 取得失敗: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        supported?.let { s ->
            logger.log(CommLogger.Direction.INFO,
                "対応 PID: " + s.sorted().joinToString(" ") { "%02X".format(it) })
        }
    }

    fun isAvailable(def: PidDef): Boolean = when (def.kind) {
        PidDef.Kind.MODE01 -> supported?.contains(def.pid) ?: true
        PidDef.Kind.ADAPTER_VOLTAGE -> adapterVoltageSupported
        PidDef.Kind.DERIVED -> def.deps.mapNotNull { Pids.get(it) }.all { isAvailable(it) }
    }

    /**
     * [defs] の値を読み、[values] を更新する。読めなかった項目は値を削除する。
     * @return 実際に通信して成功した件数と失敗した件数
     */
    fun poll(defs: Collection<PidDef>, values: MutableMap<String, Double>): Result {
        cycle++
        // 派生項目の依存先も読む
        val direct = LinkedHashMap<String, PidDef>()
        for (d in defs) {
            if (d.kind == PidDef.Kind.DERIVED) {
                d.deps.mapNotNull { Pids.get(it) }.forEach { direct[it.key] = it }
            } else {
                direct[d.key] = d
            }
        }
        var ok = 0
        var failed = 0
        for (d in direct.values) {
            if (!isAvailable(d)) { values.remove(d.key); continue }
            // 遅い項目は 8 サイクルに 1 回 (値が無いうちは毎回)
            if (d.slow && values.containsKey(d.key) && cycle % 8 != 0L) continue
            // 連続 3 回失敗した項目は 20 サイクルに 1 回だけ再試行
            val streak = failStreak[d.key] ?: 0
            if (streak >= 3 && cycle % 20 != 0L) continue

            val v: Double? = when (d.kind) {
                PidDef.Kind.MODE01 -> protocol.readPid(d.pid)
                    ?.takeIf { it.size >= d.minBytes }
                    ?.let { runCatching { d.decode(it) }.getOrNull() }
                PidDef.Kind.ADAPTER_VOLTAGE -> protocol.readAdapterVoltage().also {
                    if (it == null && values["BATTERY"] == null && streak >= 2) adapterVoltageSupported = false
                }
                PidDef.Kind.DERIVED -> null
            }
            if (v != null) {
                values[d.key] = v
                failStreak[d.key] = 0
                ok++
            } else {
                values.remove(d.key)
                failStreak[d.key] = streak + 1
                failed++
            }
        }
        for (d in defs) {
            if (d.kind == PidDef.Kind.DERIVED) {
                val v = d.derive(values)
                if (v != null) values[d.key] = v else values.remove(d.key)
            }
        }
        return Result(ok, failed)
    }

    data class Result(val ok: Int, val failed: Int)
}
