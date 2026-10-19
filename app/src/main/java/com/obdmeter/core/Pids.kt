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
    /** 表示の丸め単位 (0 なら丸めない)。回転数は 10rpm 単位にして数字の震えを抑える。 */
    val displayStep: Double = 0.0,
    /** 変化が遅い項目は数サイクルに 1 回だけ読む (更新速度を稼ぐため)。 */
    val slow: Boolean = false,
    val decode: (IntArray) -> Double = { 0.0 },
    val deps: List<String> = emptyList(),
    val derive: (Map<String, Double>) -> Double? = { null },
    /** CUSTOM のときの問い合わせ内容 */
    val request: PidRequest? = null,
    /** BMW_BLOCK: DME の測定値 ID とバイト数 ([decode] にはこの項目のバイトだけを渡す) */
    val blockId: Int = 0,
    val blockSize: Int = 0,
) {
    /**
     * MODE01: 標準 PID / ADAPTER_VOLTAGE: アダプター測定 / DERIVED: 計算値 /
     * CUSTOM: ユーザー登録のカスタム PID / REQUEST: Mode 01 以外の標準要求 (Mode 06 の失火カウンタなど)
     */
    enum class Kind { MODE01, ADAPTER_VOLTAGE, DERIVED, CUSTOM, REQUEST, BMW_BLOCK }

    val isCustom: Boolean get() = kind == Kind.CUSTOM

    fun format(value: Double?): String =
        if (value == null) "---" else "%.${decimals}f".format(value)
}

object Pids {
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

    /**
     * BMW MSD80/81 の生涯失火数 (DME の測定値 0x4850〜0x4855。点火順 1-5-3-6-2-4 で並ぶ)。
     * 測定値ブロック F0 に 6 つを登録 (2C F0 …) してから 21 F0 でまとめて読む。BMW 方式 (6F1 + 拡張アドレス 12)
     */
    private val MISFIRE_LIFE_IDS = listOf(0x4850, 0x4851, 0x4852, 0x4853, 0x4854, 0x4855)
    private val MISFIRE_LIFE_CYL = listOf(1, 5, 3, 6, 2, 4)

    /**
     * BMW の DME 測定値 (MSD80/81)。測定値ブロック F0 に読みたい測定値を登録 (2C F0 …) してから
     * 21 F0 でまとめて読む。登録する測定値は、その時に読む項目から [bmwBlockRequest] で組み立てる。
     * BMW 方式 (6F1 + 拡張アドレス 12)
     */
    fun bmwBlockRequest(items: List<PidDef>, lid: Int = 0xF0): PidRequest {
        val define = mutableListOf(0x2C, lid)
        var pos = 1
        for (d in items) {
            define += listOf(0x02, pos, d.blockSize, d.blockId shr 8, d.blockId and 0xFF, 0x01)
            pos += d.blockSize
        }
        return PidRequest(
            0x21, intArrayOf(lid), header = "6F1", canExtAddress = "12",
            setup = listOf(intArrayOf(0x2C, lid, 0x04), define.toIntArray()),
        )
    }

    private fun u16(b: IntArray) = (b[0] * 256 + b[1]).toDouble()
    private fun s16(b: IntArray) = ((b[0] * 256 + b[1]).let { if (it >= 0x8000) it - 0x10000 else it }).toDouble()

    private fun bmw(
        key: String, name: String, short: String, unit: String, min: Double, max: Double,
        id: Int, size: Int, decimals: Int = 0, warnAbove: Double? = null, warnBelow: Double? = null,
        decode: (IntArray) -> Double,
    ) = PidDef(
        key = key, name = name, shortName = short, unit = unit, min = min, max = max, decimals = decimals,
        warnAbove = warnAbove, warnBelow = warnBelow,
        kind = PidDef.Kind.BMW_BLOCK, blockId = id, blockSize = size, minBytes = size, decode = decode,
    )

    /** 生涯失火数 (DME の測定値 0x4850〜0x4855 は点火順 1-5-3-6-2-4 で並ぶ) */
    private fun misfireLife(cyl: Int): PidDef {
        val id = 0x4850 + listOf(1, 5, 3, 6, 2, 4).indexOf(cyl)
        return bmw("MISFIRE_LIFE_$cyl", "生涯失火数 #$cyl (BMW)", "生涯失火#$cyl", "回", 0.0, 100.0, id, 2) { u16(it) }
    }

    /** オイルセンサーのレベル (1 = 0.293 mm) */
    private const val OIL_LEVEL_STEP = 0.29296875

    /** オイルレベル (現在値): STATUS_OEL (30 0E 01) の応答 = 油温 2 / レベル 1 / 品質 2 バイト */
    private fun oilLevelNow() = PidDef(
        key = "OIL_LEVEL", name = "オイルレベル (BMW)", shortName = "オイル量", unit = "mm",
        min = 0.0, max = 75.0, decimals = 1,
        kind = PidDef.Kind.REQUEST,
        request = PidRequest(0x30, intArrayOf(0x0E, 0x01), header = "7E0"),
        minBytes = 3, slow = true,
        decode = { d -> d[2] * OIL_LEVEL_STEP },
    )

    /** BMW 固有の項目 (DME の測定値・オイルレベルなど)。項目を選ぶ一覧で先頭に出す */
    fun isBmwSpecific(d: PidDef): Boolean = d.kind == PidDef.Kind.BMW_BLOCK || d.name.contains("BMW")

    /** MSD80 の測定値一覧 ([MSD80_TABLE]) をすべて項目にする (キーは MSD80_xxxx) */
    private fun msd80Items(): List<PidDef> = MSD80_TABLE.flatMap { it.lines() }.mapNotNull { line ->
        val f = line.split('|')
        if (f.size < 9) return@mapNotNull null
        val id = f[0].toIntOrNull(16) ?: return@mapNotNull null
        val name = f[1]
        val mul = f[4].toDoubleOrNull() ?: return@mapNotNull null
        val add = f[5].toDoubleOrNull() ?: 0.0
        val (size, raw) = when (f[3]) {
            "u8" -> 1 to { b: IntArray -> b[0].toDouble() }
            "s8" -> 1 to { b: IntArray -> (if (b[0] >= 0x80) b[0] - 0x100 else b[0]).toDouble() }
            "u16" -> 2 to { b: IntArray -> u16(b) }
            "s16" -> 2 to { b: IntArray -> s16(b) }
            "u32" -> 4 to { b: IntArray -> ((b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()).toDouble() }
            else -> return@mapNotNull null
        }
        val short = if (name.length <= 14) name else name.take(13) + "…"
        bmw(
            "MSD80_%04X".format(id), "$name (BMW %04X)".format(id), short, f[2],
            f[6].toDoubleOrNull() ?: 0.0, f[7].toDoubleOrNull() ?: 100.0, id, size, decimals = f[8].toIntOrNull() ?: 0,
        ) { b -> raw(b) * mul + add }
    }

    /**
     * MSD80 の状態読み出しジョブ ([MSD80_STATUS_TABLE]) の結果をすべて項目にする (キーは MSD80S_ジョブ_結果)。
     * 同じジョブの項目は同じ要求を共有するので、1 サイクルに 1 回だけ問い合わせる
     */
    private fun msd80StatusItems(): List<PidDef> {
        val requests = HashMap<String, PidRequest>()
        return MSD80_STATUS_TABLE.flatMap { it.lines() }.mapNotNull { line ->
            val f = line.split('|')
            if (f.size < 14) return@mapNotNull null
            val req = f[1]
            val bytes = req.chunked(2).mapNotNull { it.toIntOrNull(16) }
            if (bytes.size != 3) return@mapNotNull null
            val request = requests.getOrPut(req) { PidRequest(bytes[0], intArrayOf(bytes[1], bytes[2]), header = "7E0") }
            val pos = f[5].toIntOrNull() ?: return@mapNotNull null
            val size = f[6].toIntOrNull() ?: return@mapNotNull null
            val signed = f[7] == "S"
            val mul = f[8].toDoubleOrNull() ?: return@mapNotNull null
            val add = f[9].toDoubleOrNull() ?: 0.0
            val name = f[2]
            PidDef(
                key = "MSD80S_${f[0]}_${f[3]}", name = "$name (BMW $req)",
                shortName = if (name.length <= 14) name else name.take(13) + "…", unit = f[4],
                min = f[10].toDoubleOrNull() ?: 0.0, max = f[11].toDoubleOrNull() ?: 100.0, decimals = f[12].toIntOrNull() ?: 0,
                kind = PidDef.Kind.REQUEST, request = request, minBytes = pos + size,
                decode = { d ->
                    var raw = 0L
                    for (i in 0 until size) raw = (raw shl 8) or d[pos + i].toLong()
                    if (signed && raw >= (1L shl (8 * size - 1))) raw -= 1L shl (8 * size)
                    raw * mul + add
                },
            )
        }
    }

    private val BMW_ITEMS: List<PidDef> by lazy {
        (1..6).map { misfireLife(it) } + listOf(
            bmw("OIL_LEVEL_LONG", "オイルレベル 長期平均 (BMW)", "オイル量平均", "mm", 0.0, 75.0, 0x4400, 1, decimals = 1) { it[0] * OIL_LEVEL_STEP },
            bmw("BMW_COOLANT", "水温 (BMW)", "水温", "°C", 40.0, 130.0, 0x581F, 1, warnAbove = 112.0) { it[0] * 0.75 - 48 },
            bmw("BMW_BATTERY", "バッテリー電圧 (BMW)", "電圧", "V", 10.0, 16.0, 0x460A, 2, decimals = 1, warnBelow = 12.0) { u16(it) * 0.015 },
            bmw("BMW_RPM", "エンジン回転数 (BMW)", "回転数", "rpm", 0.0, 8000.0, 0x4807, 2, warnAbove = 7000.0) { u16(it) },
            bmw("BMW_IAT", "吸気温度 (BMW)", "吸気温", "°C", -20.0, 80.0, 0x4200, 1, warnAbove = 60.0) { it[0] * 0.75 - 48 },
            bmw("BMW_IAT_CHARGE", "吸気温度 過給側 (BMW)", "吸気温(過給)", "°C", -20.0, 100.0, 0x58D5, 1, warnAbove = 70.0) { it[0] - 48.0 },
            bmw("BMW_AFR1", "空燃比 バンク1 (BMW)", "AFR1", "", 10.0, 20.0, 0x5A50, 2, decimals = 1) { u16(it) / 1024.0 * 14.7 },
            bmw("BMW_AFR2", "空燃比 バンク2 (BMW)", "AFR2", "", 10.0, 20.0, 0x5A51, 2, decimals = 1) { u16(it) / 1024.0 * 14.7 },
            bmw("BMW_LAMBDA1", "ラムダ バンク1 (BMW)", "λ1", "λ", 0.7, 1.3, 0x5A50, 2, decimals = 2) { u16(it) / 1024.0 },
            bmw("BMW_LAMBDA2", "ラムダ バンク2 (BMW)", "λ2", "λ", 0.7, 1.3, 0x5A51, 2, decimals = 2) { u16(it) / 1024.0 },
            bmw("BMW_PEDAL", "アクセルペダル開度 (BMW)", "アクセル", "%", 0.0, 100.0, 0x5A29, 1) { it[0] * 0.390625 },
            bmw("BMW_OIL_PRESSURE", "油圧 (BMW)", "油圧", "bar", 0.0, 6.0, 0x5A25, 2, decimals = 1) { s16(it) / 1000.0 },
        ) + msd80Items() + msd80StatusItems() + listOf(
            // ブースト = スロットル前の圧力 (過給圧、絶対圧) − 大気圧
            PidDef("BMW_BOOST_BAR", "ブースト (BMW, bar)", "ブースト", "bar", -1.0, 1.5, decimals = 2,
                kind = PidDef.Kind.DERIVED, deps = listOf("MSD80_5A2C", "MSD80_4201"),
                derive = { v -> boostHpa(v)?.let { it / 1000.0 } }),
            PidDef("BMW_BOOST_PSI", "ブースト (BMW, psi)", "ブースト", "psi", -15.0, 22.0, decimals = 1,
                kind = PidDef.Kind.DERIVED, deps = listOf("MSD80_5A2C", "MSD80_4201"),
                derive = { v -> boostHpa(v)?.let { it / 1000.0 * 14.503774 } }),
        )
    }

    /** ブースト (hPa)。大気圧が読めていなければ 1013 hPa とみなす */
    private fun boostHpa(v: Map<String, Double>): Double? {
        val p = v["MSD80_5A2C"] ?: return null
        val baro = v["MSD80_4201"]?.takeIf { it in 500.0..1200.0 } ?: 1013.25
        return p - baro
    }

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

    val ALL: List<PidDef> = listOf(
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
        oilLevelNow(),
        // 排気フラップ (STATUS_AGK, 30 C1 01): LV_EF = 0 / 1
        PidDef(
            key = "BMW_EXHAUST_FLAP", name = "排気フラップ 作動状態 (BMW)", shortName = "排気フラップ", unit = "",
            min = 0.0, max = 1.0, decimals = 0,
            kind = PidDef.Kind.REQUEST,
            request = PidRequest(0x30, intArrayOf(0xC1, 0x01), header = "7E0"),
            minBytes = 1,
            decode = { d -> if (d[0] != 0) 1.0 else 0.0 },
        ),
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
    ) + BMW_ITEMS

    private val byKey = ALL.associateBy { it.key }

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

/**
 * プロトコルを使って、表示中の項目を 1 サイクル分読み出す。
 *
 * - 一時的な読み出し失敗では直前の値を保持する (失敗のたびに "---" に戻るとメーターがちらつくため)。
 *   [HOLD_FAILS] 回連続で失敗したら値を消す。
 * - 対応 PID 一覧で「非対応」の項目も、画面に置かれていれば時々試す。読めたら対応扱いに切り替える。
 * - 成功⇔失敗が切り替わったときだけ、理由付きでログに出す。
 */
class ObdReader(
    private val protocol: ObdProtocol,
    private val logger: CommLogger = CommLogger.NONE,
) {
    /** 車両が対応している Mode 01 PID。取得できなかった場合は null (= 全部試す)。 */
    var supported: Set<Int>? = null
        private set

    private val failStreak = mutableMapOf<String, Int>()
    private val failing = mutableSetOf<String>()
    /** 直近の失敗が「ECU の拒否」だった項目 */
    private val refusedKeys = mutableSetOf<String>()
    private var cycle = 0L
    /** 1 サイクル内で同じ要求 (測定値ブロックなど) を何度も送らないための控え */
    private val requestCache = HashMap<PidRequest, IntArray?>()
    private var adapterVoltageSupported = true
    /** 項目ごとの取得周期 (ミリ秒)。この間隔より早くは読み直さない (値があるとき) */
    var intervals: Map<String, Long> = emptyMap()
    /** 項目ごとの最後に読んだ時刻 */
    private val lastRead = HashMap<String, Long>()
    /** 1 サイクルの中で使う、派生項目の周期を依存先にも当てたもの */
    private var effIntervals: Map<String, Long> = emptyMap()

    /** 取得周期がまだ来ていない (値があって前回から周期が経っていない) */
    private fun notDue(key: String, values: Map<String, Double>, now: Long): Boolean {
        val iv = effIntervals[key] ?: return false
        if (iv <= 0 || !values.containsKey(key)) return false
        val last = lastRead[key] ?: return false
        return now - last < iv
    }

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
        PidDef.Kind.CUSTOM, PidDef.Kind.REQUEST, PidDef.Kind.BMW_BLOCK -> true
    }

    /**
     * [defs] の値を読み、[values] を更新する。
     * [fallbacks] は「メイン項目のキー → 代替項目」。メイン項目の値が無いとき (イグニッション OFF で
     * 標準 PID が拒否されるときなど) だけ代替項目を読む。メインが読めている間は代替は読まない。
     * @return 実際に通信して成功した件数と失敗した件数
     */
    fun poll(
        defs: Collection<PidDef>,
        values: MutableMap<String, Double>,
        fallbacks: Map<String, PidDef> = emptyMap(),
        /** true = 失敗続きの項目の間引きをせず、すべて読む (グラフの記録用の 1 回読み) */
        forceAll: Boolean = false,
    ): Result {
        cycle++
        requestCache.clear()
        // 派生項目の依存先も読む
        val direct = LinkedHashMap<String, PidDef>()
        for (d in defs) {
            if (d.kind == PidDef.Kind.DERIVED) {
                d.deps.mapNotNull { Pids.get(it) }.forEach { direct[it.key] = it }
            } else {
                direct[d.key] = d
            }
        }
        val c = Counter()
        // 取得周期: 派生項目の周期は依存先にも当てる (依存先に自分の設定があればそちら)
        effIntervals = if (forceAll || intervals.isEmpty()) emptyMap() else HashMap(intervals).also { m ->
            for (d in defs) if (d.kind == PidDef.Kind.DERIVED) intervals[d.key]?.let { iv ->
                d.deps.forEach { dep -> if (dep !in intervals) m[dep] = minOf(m[dep] ?: Long.MAX_VALUE, iv) }
            }
        }
        val now = System.currentTimeMillis()
        // BMW の測定値はまとめて 1 回で読む (どれも周期が来ていなければ読まない)
        val block = direct.values.filter { it.kind == PidDef.Kind.BMW_BLOCK }
        for (d in direct.values) if (d.kind != PidDef.Kind.BMW_BLOCK) {
            if (!forceAll && notDue(d.key, values, now)) continue
            readItem(d, values, c, forceAll)
            lastRead[d.key] = now
        }
        if (block.isNotEmpty()) {
            if (forceAll) {
                // 記録用の 1 回読み: 表示用の登録を崩さないよう、別の番号 (F9) に登録して読む
                readBlock(block, values, c, true, lid = if (multiBlockUnsupported) 0xF0 else 0xF9)
            } else if (multiBlockUnsupported || effIntervals.isEmpty()) {
                // 周期の設定が無い (または DME が 1 つしか登録できない): まとめて 1 回。どれか周期が来ていれば読む
                if (block.any { !notDue(it.key, values, now) }) {
                    readBlock(block, values, c, false)
                    block.forEach { lastRead[it.key] = now }
                }
            } else {
                // 取得周期ごとに分けて別の番号 (F0, F1, …) に登録し、周期が来たまとまりだけ読む
                var groups = block.groupBy { effIntervals[it.key] ?: 0L }.toSortedMap().values.toList()
                // 番号は F0〜F8 まで (F9 は記録用)。それより多い周期は最後のまとまりに入れる
                if (groups.size > 9) groups = groups.take(8) + listOf(groups.drop(8).flatten())
                groups.forEachIndexed { i, g ->
                    if (g.any { !notDue(it.key, values, now) }) {
                        readBlock(g, values, c, false, lid = 0xF0 + i)
                        g.forEach { lastRead[it.key] = now }
                    }
                }
            }
        }
        for (d in defs) {
            if (d.kind == PidDef.Kind.DERIVED) {
                val v = d.derive(values)
                if (v != null) values[d.key] = v else values.remove(d.key)
            }
        }
        // 代替項目: メインの値が無いときだけ読む
        var fallbackOk = 0
        for ((primary, fb) in fallbacks) {
            if (values.containsKey(primary)) {
                values.remove(fb.key)
                continue
            }
            val fc = Counter()
            readItem(fb, values, fc)
            fallbackOk += fc.ok
            c.failed += fc.failed
            c.refused += fc.refused
        }
        return Result(c.ok + fallbackOk, c.failed, c.refused, c.stdOk, c.stdRefused)
    }

    private class Counter {
        var ok = 0
        var failed = 0
        var refused = 0
        /** 標準 PID (Mode 01) だけの集計。イグニッション OFF の判定に使う */
        var stdOk = 0
        var stdRefused = 0
    }

    /** DME が登録を断った測定値 (キー → 断られたサイクル)。しばらくまとめ読みから外す */
    private val blockRejected = mutableMapOf<String, Long>()

    /**
     * BMW の測定値をまとめて読む。登録が断られたら 1 つずつ試し、断られた測定値だけを外す
     * (DME のソフトによって対応している測定値が違うため)
     */
    /** DME が F0 以外の番号への登録を断った (1 つしか登録できない)。以後はまとめて F0 で読む */
    var multiBlockUnsupported = false
        private set

    private fun readBlock(items: List<PidDef>, values: MutableMap<String, Double>, c: Counter, forceAll: Boolean, lid: Int = 0xF0) {
        val use = items.filter { d -> blockRejected[d.key]?.let { forceAll || cycle - it >= 300 } ?: true }
        for (d in items - use.toSet()) values.remove(d.key)
        if (use.isEmpty()) return
        // 同じ測定値は 1 回だけ登録し、ID 順に並べる (ページの並びが変わっても登録をやり直さない)
        val ids = use.distinctBy { it.blockId }.sortedBy { it.blockId }
        val offsets = HashMap<Int, Int>()
        var total = 0
        for (d in ids) { offsets[d.blockId] = total; total += d.blockSize }
        val data = protocol.query(Pids.bmwBlockRequest(ids, lid))
        if (data != null && data.size >= total) {
            for (d in use) {
                val off = offsets.getValue(d.blockId)
                val v = runCatching { d.decode(data.copyOfRange(off, off + d.blockSize)) }.getOrNull()
                if (v != null) {
                    values[d.key] = v
                    blockRejected.remove(d.key)
                    failStreak[d.key] = 0
                    c.ok++
                } else {
                    values.remove(d.key); c.failed++
                }
            }
            return
        }
        val refused = protocol.lastResponded
        if (!refused) {
            // 応答なし (通信の一時的な失敗など): 直前の値は残し、次のサイクルでまた試す
            for (d in use) {
                val s = (failStreak[d.key] ?: 0) + 1
                failStreak[d.key] = s
                if (s >= HOLD_FAILS) values.remove(d.key)
            }
            c.failed += use.size
            return
        }
        if (lid != 0xF0) {
            // F0 以外の番号が使えない DME かもしれない: 測定値のせいにせず、以後は F0 だけで読む
            multiBlockUnsupported = true
            logger.log(CommLogger.Direction.INFO, "この DME は測定値の登録を 1 つしか持てないため、まとめて読みます")
            return
        }
        c.refused += use.size
        if (use.size == 1) {
            blockRejected[use[0].key] = cycle
            values.remove(use[0].key)
            logger.log(CommLogger.Direction.ERROR, "${use[0].shortName}: この DME は測定値 %04X に対応していません".format(use[0].blockId))
            return
        }
        // どれが断られたかを 1 つずつ確かめる
        for (d in use) readBlock(listOf(d), values, c, forceAll, lid)
    }

    private fun readItem(d: PidDef, values: MutableMap<String, Double>, c: Counter, forceAll: Boolean = false) {
        if (d.kind == PidDef.Kind.BMW_BLOCK) { readBlock(listOf(d), values, c, forceAll); return }
        val streak = failStreak[d.key] ?: 0
        if (forceAll) {
            // 間引きなし
        } else if (!isAvailable(d)) {
            // 非対応扱いでも、最初と 50 サイクルごとに一度だけ試す
            if (d.kind != PidDef.Kind.MODE01 || (cycle != 1L && cycle % 50 != 0L)) {
                values.remove(d.key); return
            }
        } else {
            // 遅い項目は 8 サイクルに 1 回 (値が無いうちは毎回)
            if (d.slow && d.key !in effIntervals && values.containsKey(d.key) && cycle % 8 != 0L) return
            // 連続 3 回失敗した項目は 20 サイクルに 1 回だけ再試行。
            // ただし ECU が拒否しただけ (イグニッション OFF の 7F 01 22) なら毎回試す。
            // そうしないとイグニッション ON にしても値が戻るまで時間がかかる
            if (streak >= HOLD_FAILS && d.key !in refusedKeys && cycle % 20 != 0L) return
        }

        val v: Double? = when (d.kind) {
            PidDef.Kind.MODE01 -> protocol.readPid(d.pid)
                ?.takeIf { it.size >= d.minBytes }
                ?.let { runCatching { d.decode(it) }.getOrNull() }
            PidDef.Kind.ADAPTER_VOLTAGE -> protocol.readAdapterVoltage().also {
                if (it == null && values["BATTERY"] == null && streak >= 2) adapterVoltageSupported = false
            }
            PidDef.Kind.CUSTOM, PidDef.Kind.REQUEST -> requestCache.getOrPut(d.request!!) { protocol.query(d.request) }
                ?.let { data ->
                    runCatching { d.decode(data) }
                        .onFailure { e -> if (d.key !in failing) logger.log(CommLogger.Direction.ERROR, "${d.shortName}: ${e.message}") }
                        .getOrNull()
                }
            PidDef.Kind.DERIVED, PidDef.Kind.BMW_BLOCK -> null
        }
        val std = d.kind == PidDef.Kind.MODE01
        if (v != null) {
            values[d.key] = v
            failStreak[d.key] = 0
            refusedKeys.remove(d.key)
            if (std && supported?.contains(d.pid) == false) {
                supported = supported.orEmpty() + d.pid
                logger.log(CommLogger.Direction.INFO, "${d.shortName} (PID %02X) は読めたので対応扱いにします".format(d.pid))
            }
            if (failing.remove(d.key)) logger.log(CommLogger.Direction.INFO, "${d.shortName}: 復帰")
            c.ok++
            if (std) c.stdOk++
        } else {
            if ((std || d.kind == PidDef.Kind.CUSTOM || d.kind == PidDef.Kind.REQUEST) && protocol.lastResponded) {
                c.refused++
                if (std) c.stdRefused++
                refusedKeys.add(d.key)
            } else {
                refusedKeys.remove(d.key)
            }
            failStreak[d.key] = streak + 1
            // 一時的な失敗では直前の値を残す
            if (streak + 1 >= HOLD_FAILS) values.remove(d.key)
            if (failing.add(d.key)) {
                val pidText = when (d.kind) {
                    PidDef.Kind.MODE01 -> " (PID %02X)".format(d.pid)
                    PidDef.Kind.CUSTOM, PidDef.Kind.REQUEST -> " (${d.request})"
                    else -> ""
                }
                logger.log(CommLogger.Direction.ERROR,
                    "${d.shortName}$pidText 読み出し失敗: ${protocol.lastFailure ?: "応答なし"}")
            }
            c.failed++
        }
    }

    /**
     * 1 サイクルの結果。
     * @param refused 失敗のうち、ECU が応答して拒否したもの (イグニッション OFF の 7F 01 22 など)
     * @param stdOk / stdRefused 標準 PID だけの成功数・拒否数 (イグニッション OFF の判定用)
     */
    data class Result(
        val ok: Int,
        val failed: Int,
        val refused: Int = 0,
        val stdOk: Int = 0,
        val stdRefused: Int = 0,
    ) {
        /** ECU から何らかの応答があった (= 車両とはつながっている) */
        val vehicleAlive: Boolean get() = ok > 0 || refused > 0

        /** 標準 PID がすべて拒否されている (= イグニッション OFF)。カスタム PID は読めていてもよい */
        val ignitionOff: Boolean get() = stdOk == 0 && stdRefused > 0
    }

    companion object {
        const val HOLD_FAILS = 3
    }
}
