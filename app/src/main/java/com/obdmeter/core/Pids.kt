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
) {
    /**
     * MODE01: 標準 PID / ADAPTER_VOLTAGE: アダプター測定 / DERIVED: 計算値 /
     * CUSTOM: ユーザー登録のカスタム PID / REQUEST: Mode 01 以外の標準要求 (Mode 06 の失火カウンタなど)
     */
    enum class Kind { MODE01, ADAPTER_VOLTAGE, DERIVED, CUSTOM, REQUEST }

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
        misfire("MISFIRE_TOTAL", "失火カウンタ 合計", "失火計", 0xA1),
        misfire("MISFIRE_1", "失火カウンタ #1", "失火#1", 0xA2),
        misfire("MISFIRE_2", "失火カウンタ #2", "失火#2", 0xA3),
        misfire("MISFIRE_3", "失火カウンタ #3", "失火#3", 0xA4),
        misfire("MISFIRE_4", "失火カウンタ #4", "失火#4", 0xA5),
        misfire("MISFIRE_5", "失火カウンタ #5", "失火#5", 0xA6),
        misfire("MISFIRE_6", "失火カウンタ #6", "失火#6", 0xA7),
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
        PidDef.Kind.CUSTOM, PidDef.Kind.REQUEST -> true
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
    ): Result {
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
        val c = Counter()
        for (d in direct.values) readItem(d, values, c)
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

    private fun readItem(d: PidDef, values: MutableMap<String, Double>, c: Counter) {
        val streak = failStreak[d.key] ?: 0
        if (!isAvailable(d)) {
            // 非対応扱いでも、最初と 50 サイクルごとに一度だけ試す
            if (d.kind != PidDef.Kind.MODE01 || (cycle != 1L && cycle % 50 != 0L)) {
                values.remove(d.key); return
            }
        } else {
            // 遅い項目は 8 サイクルに 1 回 (値が無いうちは毎回)
            if (d.slow && values.containsKey(d.key) && cycle % 8 != 0L) return
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
            PidDef.Kind.CUSTOM, PidDef.Kind.REQUEST -> protocol.query(d.request!!)
                ?.let { data ->
                    runCatching { d.decode(data) }
                        .onFailure { e -> if (d.key !in failing) logger.log(CommLogger.Direction.ERROR, "${d.shortName}: ${e.message}") }
                        .getOrNull()
                }
            PidDef.Kind.DERIVED -> null
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
