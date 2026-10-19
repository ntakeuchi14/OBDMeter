package com.obdmeter.core

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
    /** 現在時刻 (ミリ秒)。テストで差し替える */
    private val clock: () -> Long = System::currentTimeMillis,
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
        PidDef.Kind.CUSTOM, PidDef.Kind.REQUEST, PidDef.Kind.BMW_BLOCK, PidDef.Kind.DEVICE -> true
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
            when (d.kind) {
                PidDef.Kind.DERIVED -> d.deps.mapNotNull { Pids.get(it) }.forEach { direct[it.key] = it }
                PidDef.Kind.DEVICE -> {} // 端末のセンサーは車に問い合わせない
                else -> direct[d.key] = d
            }
        }
        val c = Counter()
        effIntervals = if (forceAll) emptyMap() else PollPlan.effectiveIntervals(defs, intervals)
        val now = clock()
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
                readBlock(block, values, c, true, lid = if (multiBlockUnsupported) PollPlan.LID_FIRST else PollPlan.LID_SAMPLE)
            } else if (multiBlockUnsupported || effIntervals.isEmpty()) {
                // 周期の設定が無い (または DME が 1 つしか登録できない): まとめて 1 回。どれか周期が来ていれば読む
                if (block.any { !notDue(it.key, values, now) }) {
                    readBlock(block, values, c, false)
                    block.forEach { lastRead[it.key] = now }
                }
            } else {
                // 取得周期ごとに分けて別の番号 (F0, F1, …) に登録し、周期が来たまとまりだけ読む
                for ((lid, g) in PollPlan.blockGroups(block, effIntervals)) {
                    if (g.any { !notDue(it.key, values, now) }) {
                        readBlock(g, values, c, false, lid = lid)
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

    private fun readBlock(items: List<PidDef>, values: MutableMap<String, Double>, c: Counter, forceAll: Boolean, lid: Int = PollPlan.LID_FIRST) {
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
        if (lid != PollPlan.LID_FIRST) {
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
            PidDef.Kind.DERIVED, PidDef.Kind.BMW_BLOCK, PidDef.Kind.DEVICE -> null
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
