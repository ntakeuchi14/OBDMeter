package com.obdmeter.data

/**
 * 変動監視 (メーターの種類 [GaugeStyle.MONITOR]) の集計 (データベースは使わない)。
 *
 * 記録は項目ごとに「値が変わった時刻と値」の行で、最初の行は初回の計測値 (変化には数えない)。
 * 記録はメーターを削除するまで消さない
 */
object Monitor {
    /** 変動回数を数える期間 (直近 ○○) */
    val WINDOWS: List<Pair<Long, String>> = listOf(
        10_000L to "10秒",
        60_000L to "1分",
        600_000L to "10分",
        3_600_000L to "1時間",
        86_400_000L to "1日",
        7 * 86_400_000L to "1週間",
        HistoryStore.MONTH_MS to "1か月",
        3 * HistoryStore.MONTH_MS to "3か月",
        6 * HistoryStore.MONTH_MS to "6か月",
        24 * HistoryStore.MONTH_MS to "24か月",
    )

    /**
     * 項目 1 つの集計。[first] = 初回の計測値、[last] = 最後に記録した値、[lastChangeAt] = 最後に変わった時刻
     * (変わったことが無ければ null)、[counts] = [WINDOWS] の各期間の変動回数
     */
    data class Stat(
        val key: String,
        val first: Double?,
        val last: Double?,
        val lastChangeAt: Long?,
        val counts: List<Int>,
    ) {
        fun count(window: Int): Int = counts.getOrElse(window) { 0 }
    }

    /** 期間 (ミリ秒) [ms] の [WINDOWS] での番号 (無ければ 1 日) */
    fun windowIndex(ms: Long): Int = WINDOWS.indexOfFirst { it.first == ms }.takeIf { it >= 0 }
        ?: WINDOWS.indexOfFirst { it.first == 86_400_000L }

    /** 期間 [window] の変動回数が多い順。同じ回数なら登録順 ([stats] の並び) */
    fun rank(stats: List<Stat>, window: Int): List<Stat> =
        stats.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Stat>> { it.value.count(window) }.thenBy { it.index })
            .map { it.value }

    /**
     * 現在の値 = 最新の計測値 - 初回の計測値。最新の計測値は今読めている値 [live] (無ければ最後に記録した値)
     */
    fun currentValue(stat: Stat?, live: Double?): Double? {
        val first = stat?.first ?: return null
        val latest = live ?: stat.last ?: return null
        return latest - first
    }

    /**
     * 記録する行を決める。前回記録した値 [last] (まだ記録が無ければ null) から変わったときだけ、時刻 [time] の値 [v] を記録する。
     * [increaseOnly] なら、前回より大きくなったときだけ記録する (減った値は無視し、前回の値のまま比べ続ける)
     */
    fun shouldRecord(v: Double, last: Double?, increaseOnly: Boolean = false): Boolean =
        !v.isNaN() && !v.isInfinite() && v != last && !(increaseOnly && last != null && v < last)
}
