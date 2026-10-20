package com.obdmeter.data

import com.obdmeter.core.PidCatalog
import com.obdmeter.core.PidDef

/**
 * どの項目を読むか・記録するかを決める計算 (通信やデータベースは使わない)。[com.obdmeter.AppCore] が使う
 */
object ReadPlan {
    /** メーターが使う項目のキー (グラフ・値テーブルは追加した項目も) */
    fun gaugeKeys(g: GaugeConfig): List<String> =
        if (g.style == GaugeStyle.CHART || g.style == GaugeStyle.TABLE) g.chartAllKeys else listOf(g.pidKey)

    /**
     * 表示中のページ。アプリの画面が前にあればアプリのページ、無ければフロート表示のページ。
     * まだ何も表示していないとき (起動直後) はアプリのページ
     */
    fun shownPages(pages: List<Dashboard>, appVisible: Boolean, appPage: Long?, floatPage: Long?): List<Dashboard> =
        listOfNotNull(if (appVisible) appPage else floatPage)
            .mapNotNull { id -> pages.firstOrNull { it.id == id } }
            .ifEmpty { listOfNotNull(pages.firstOrNull { it.id == appPage }) }

    /** 表示中のページで読む項目 (重複なし) と、代替項目 (メイン項目のキー → 代替項目) */
    fun activeItems(shown: List<Dashboard>, catalog: PidCatalog): Pair<List<PidDef>, Map<String, PidDef>> {
        val gauges = shown.flatMap { it.gauges }
        val pids = gauges.flatMap(::gaugeKeys).mapNotNull { catalog.get(it) }.distinctBy { it.key }
        val fallbacks = gauges.mapNotNull { g -> g.fallbackKey?.let { fb -> catalog.get(fb)?.let { g.pidKey to it } } }.toMap()
        return pids to fallbacks
    }

    /** 変化したときだけ記録する項目を見る最短の間隔 (表示中で値が常に届いている項目) */
    const val CHANGE_MIN_MS = 200L
    /** 変化したときだけ記録する項目を、表示していないので記録のために読むときの最短の間隔 */
    const val CHANGE_SAMPLE_MIN_MS = 1_000L
    /** 変化したときだけ記録する項目でも、変化が無いままこの時間が過ぎたら同じ値を記録する (線を途切れさせない) */
    const val CHANGE_HEARTBEAT_MS = 60_000L

    /**
     * 記録のしかた。[intervalMs] ごとに値を見て、[onChangeOnly] なら前回記録した値から変わったときだけ記録する
     */
    data class RecordRule(val intervalMs: Long, val onChangeOnly: Boolean)

    /**
     * 記録する項目と記録のしかた。同じ項目を複数のグラフで使うときは短いほうの間隔で、
     * 「変化したときだけ」は使っているグラフが全部そうなっているときだけ。
     * 「変化したときだけ」のグラフでは、項目の取得周期 ([pollIntervals]、無ければ [CHANGE_MIN_MS]) ごとに値を見る。
     * 「表示中だけ記録する」グラフは、そのページ ([shownPage]) を表示している間だけ数える
     */
    fun recordRules(pages: List<Dashboard>, shownPage: Long?, pollIntervals: Map<String, Long> = emptyMap()): Map<String, RecordRule> {
        val iv = HashMap<String, Long>()
        val fixed = HashSet<String>()
        for (p in pages) for (g in p.gauges) {
            if (g.style != GaugeStyle.CHART) continue
            if (g.design.recordOnlyWhenShown && p.id != shownPage) continue
            val sec = g.design.recordIntervalSec.toInt().coerceIn(HistoryStore.MIN_INTERVAL_SEC, HistoryStore.MAX_INTERVAL_SEC)
            for (k in g.chartAllKeys) {
                val ms = if (g.design.recordOnChange) maxOf(pollIntervals[k] ?: 0L, CHANGE_MIN_MS) else sec * 1000L
                iv[k] = minOf(iv[k] ?: Long.MAX_VALUE, ms)
                if (!g.design.recordOnChange) fixed += k
            }
        }
        return iv.mapValues { (k, ms) -> RecordRule(ms, k !in fixed) }
    }

    /** 記録する項目と記録間隔 (ミリ秒)。[recordRules] の間隔だけ */
    fun recordIntervals(pages: List<Dashboard>, shownPage: Long?, pollIntervals: Map<String, Long> = emptyMap()): Map<String, Long> =
        recordRules(pages, shownPage, pollIntervals).mapValues { it.value.intervalMs }

    /**
     * 「変化したときだけ」の項目で、今回の値 [v] を記録するか。前回記録した値 [last] (時刻 [lastAt]) と違うか、
     * 変化が無いまま [CHANGE_HEARTBEAT_MS] が過ぎていれば記録する
     */
    fun shouldRecordChange(v: Double, last: Double?, lastAt: Long?, now: Long): Boolean =
        last == null || lastAt == null || v != last || now - lastAt >= CHANGE_HEARTBEAT_MS

    /** 記録を残す期間 (ミリ秒)。グラフが複数あれば一番長いもの。グラフが無ければ 3 か月 */
    fun retentionMs(pages: List<Dashboard>): Long =
        pages.flatMap { it.gauges }.filter { it.style == GaugeStyle.CHART }.maxOfOrNull { it.design.historyRetentionMs }
            ?: (3 * HistoryStore.MONTH_MS)

    /**
     * 記録する項目 [due] を、表示用にすでに読んでいて値がある項目 (そのまま使う) と、記録のために読む項目に分ける
     */
    fun splitSample(due: Collection<String>, live: Map<String, Double>, activeKeys: Set<String>): Pair<Map<String, Double>, List<String>> {
        val reuse = due.filter { it in activeKeys && live[it] != null }.associateWith { live.getValue(it) }
        return reuse to due.filter { it !in reuse }
    }
}
