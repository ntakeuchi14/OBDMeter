package com.obdmeter.core

/**
 * BMW (MSD80/81, N54) 固有の項目。
 * - 測定値 (BMW_BLOCK): 測定値ブロック (2C F0 …) に登録して 21 F0 でまとめて読む
 * - 状態読み出し (REQUEST): 30 xx 01 / 22 xx xx
 * - 計算項目 (DERIVED): ブーストなど
 */
internal object BmwItems {
    /**
     * BMW の DME 測定値 (MSD80/81) の読み出し要求。測定値ブロック [lid] (F0〜F9) に読みたい測定値を
     * 登録 (2C lid …) してから 21 lid でまとめて読む。BMW 方式 (6F1 + 拡張アドレス 12)。
     * 各測定値の登録は [02 位置 バイト数 ID上 ID下 01]
     */
    fun blockRequest(items: List<PidDef>, lid: Int = 0xF0): PidRequest {
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

    /** 排気フラップ (STATUS_AGK, 30 C1 01): LV_EF = 0 / 1 */
    private fun exhaustFlap() = PidDef(
        key = "BMW_EXHAUST_FLAP", name = "排気フラップ 作動状態 (BMW)", shortName = "排気フラップ", unit = "",
        min = 0.0, max = 1.0, decimals = 0,
        kind = PidDef.Kind.REQUEST,
        request = PidRequest(0x30, intArrayOf(0xC1, 0x01), header = "7E0"),
        minBytes = 1,
        decode = { d -> if (d[0] != 0) 1.0 else 0.0 },
    )

    /** BMW 固有の項目 (DME の測定値・オイルレベルなど)。項目を選ぶ一覧で先頭に出す */
    fun isBmwSpecific(d: PidDef): Boolean = d.kind == PidDef.Kind.BMW_BLOCK || d.name.contains("BMW")

    /** MSD80 の測定値一覧 ([MSD80_TABLE]) をすべて項目にする (キーは MSD80_xxxx) */
    internal fun msd80Items(): List<PidDef> = MSD80_TABLE.flatMap { it.lines() }.mapNotNull { line ->
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
    internal fun msd80StatusItems(): List<PidDef> {
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

    /** BMW 固有の項目すべて (手で調整した項目 → 測定値一覧 → 状態読み出し → 計算項目) */
    val ALL: List<PidDef> by lazy {
        listOf(oilLevelNow(), exhaustFlap()) + (1..6).map { misfireLife(it) } + listOf(
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
    internal fun boostHpa(v: Map<String, Double>): Double? {
        val p = v["MSD80_5A2C"] ?: return null
        val baro = v["MSD80_4201"]?.takeIf { it in 500.0..1200.0 } ?: 1013.25
        return p - baro
    }
}
