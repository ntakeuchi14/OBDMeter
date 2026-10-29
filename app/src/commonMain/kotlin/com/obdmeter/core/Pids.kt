package com.obdmeter.core

object Pids {
    /** BMW の測定値ブロックの読み出し要求 ([BmwItems.blockRequest]) */
    fun bmwBlockRequest(items: List<PidDef>, lid: Int = 0xF0): PidRequest = BmwItems.blockRequest(items, lid)

    /** BMW 固有の項目か ([BmwItems.isBmwSpecific]) */
    fun isBmwSpecific(d: PidDef): Boolean = BmwItems.isBmwSpecific(d)

    /** 車からの読み方。[BLOCK] = DME の測定値ブロックでまとめて 1 回で読める、[SINGLE] = 1 項目ずつ読む、[DEVICE] = 車から読まない */
    enum class ReadKind(val label: String) { BLOCK("まとめ読み"), SINGLE("個別読み"), DEVICE("端末") }

    fun readKind(d: PidDef, lookup: (String) -> PidDef? = ::get): ReadKind = when (d.kind) {
        PidDef.Kind.BMW_BLOCK -> ReadKind.BLOCK
        PidDef.Kind.DEVICE -> ReadKind.DEVICE
        PidDef.Kind.DERIVED -> {
            val deps = d.deps.mapNotNull(lookup).map { readKind(it, lookup) }
            when {
                deps.any { it == ReadKind.SINGLE } -> ReadKind.SINGLE
                deps.any { it == ReadKind.BLOCK } -> ReadKind.BLOCK
                deps.isEmpty() -> ReadKind.SINGLE
                else -> ReadKind.DEVICE
            }
        }
        else -> ReadKind.SINGLE
    }

    private fun a(d: IntArray) = d[0].toDouble()

    /**
     * OBD Mode 06 (SAE J1979) の失火モニター。MID A1 = 全体、A2〜AD = 気筒 1〜12。
     * 応答は「TID・単位 ID・値(2)・下限(2)・上限(2)」の 8 バイトが並ぶ。CAN の ECU は 2 件目以降の
     * 前に MID をもう一度付ける (46 A2 [0B ...] A2 [0C ...]) ので、それも読み飛ばす。
     * TID 0C = 今回の運転サイクルの失火回数、0B = 直近 10 サイクルの平均 (EWMA)。
     */
    private fun misfire(key: String, name: String, short: String, mid: Int) = PidDef(
        key = key, name = name, shortName = short, unit = "回", min = 0.0, max = 50.0,
        kind = PidDef.Kind.REQUEST, request = PidRequest(0x06, intArrayOf(mid)),
        minBytes = 8, slow = true, decode = ::decodeMisfire,
    )

    internal fun decodeMisfire(d: IntArray): Double {
        var ewma: Double? = null
        var i = 0
        while (i + 8 <= d.size) {
            val value = (d[i + 2] * 256 + d[i + 3]).toDouble()
            when (d[i]) {
                0x0C -> return value
                0x0B -> ewma = value
            }
            i += 8
            // 次の件の前に付いた MID (A1〜AD) を飛ばす
            if (i < d.size && d[i] in 0xA1..0xAD && d.size - i >= 9) i++
        }
        return ewma ?: throw IllegalArgumentException("失火カウンタの応答に該当するテストがありません")
    }
    private fun ab(d: IntArray) = (d[0] * 256 + d[1]).toDouble()

    /** 全項目 (BMW の一覧の読み込みに少し時間がかかるので、初めて使うときに作る。起動時に裏で先に作っておく) */
    val ALL: List<PidDef> by lazy { listOf(
        PidDef("RPM", "エンジン回転数", "回転数", "rpm", 0.0, 8000.0, warnAbove = 7000.0, displayStep = 10.0,
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
        misfire("MISFIRE_TOTAL", "失火カウンタ 今回の運転 合計", "失火計", 0xA1),
        misfire("MISFIRE_1", "失火カウンタ 今回の運転 #1", "失火#1", 0xA2),
        misfire("MISFIRE_2", "失火カウンタ 今回の運転 #2", "失火#2", 0xA3),
        misfire("MISFIRE_3", "失火カウンタ 今回の運転 #3", "失火#3", 0xA4),
        misfire("MISFIRE_4", "失火カウンタ 今回の運転 #4", "失火#4", 0xA5),
        misfire("MISFIRE_5", "失火カウンタ 今回の運転 #5", "失火#5", 0xA6),
        misfire("MISFIRE_6", "失火カウンタ 今回の運転 #6", "失火#6", 0xA7),
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
    ) + BmwItems.ALL + DeviceItems.ALL }

    private val byKey by lazy { ALL.associateBy { it.key } }

    fun get(key: String): PidDef? = byKey[key]

    /**
     * カスタム PID の定義を作る。式が不正な場合は [ExpressionException]、
     * モード / PID / ヘッダーが不正な場合は [IllegalArgumentException] を投げる。
     */
    fun custom(
        key: String, name: String, shortName: String, unit: String,
        mode: String, pid: String, header: String, canExtAddress: String,
        formula: String, min: Double, max: Double, decimals: Int,
        warnAbove: Double?, warnBelow: Double?, slow: Boolean,
    ): PidDef {
        val expr = Expression.compile(formula)
        val req = PidRequest.parse(mode, pid, header, canExtAddress)
        require(max > min) { "最大値は最小値より大きくしてください" }
        return PidDef(
            key = key, name = name.ifBlank { "カスタム PID" }, shortName = shortName.ifBlank { name.take(6) },
            unit = unit, min = min, max = max, decimals = decimals.coerceIn(0, 3),
            warnAbove = warnAbove, warnBelow = warnBelow, kind = PidDef.Kind.CUSTOM,
            pid = if (req.mode == 0x01 && req.pid.size == 1) req.pid[0] else -1,
            minBytes = expr.requiredBytes, slow = slow, decode = expr::evaluate, request = req,
        )
    }
}

/**
 * 標準項目とカスタム PID をまとめた一覧。キーで引ける。
 */
class PidCatalog(val custom: List<PidDef> = emptyList()) {
    private val customByKey = custom.associateBy { it.key }
    val all: List<PidDef> get() = Pids.ALL + custom
    fun get(key: String): PidDef? = customByKey[key] ?: Pids.get(key)
}
