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

    /** 記録の単位: グラフ [slot] ([HistorySlot]) の項目 [key] */
    data class RecordTarget(val slot: String, val key: String)

    /** 折れ線グラフ (ページとメーターの id 付き) */
    private fun charts(pages: List<Dashboard>): Sequence<Pair<Dashboard, GaugeConfig>> =
        pages.asSequence().flatMap { p -> p.gauges.asSequence().filter { it.style == GaugeStyle.CHART }.map { p to it } }

    /**
     * 記録するもの (グラフごと・項目ごと) と記録のしかた。記録はグラフごとに別々に持つ。
     * 「変化したときだけ」のグラフでは、項目の取得周期 ([pollIntervals]、無ければ [CHANGE_MIN_MS]) ごとに値を見る。
     * 「表示中だけ記録する」グラフは、そのページ ([shownPage]) を表示している間だけ
     */
    fun recordRules(pages: List<Dashboard>, shownPage: Long?, pollIntervals: Map<String, Long> = emptyMap()): Map<RecordTarget, RecordRule> {
        val out = HashMap<RecordTarget, RecordRule>()
        for ((p, g) in charts(pages)) {
            if (g.design.recordOnlyWhenShown && p.id != shownPage) continue
            val sec = g.design.recordIntervalSec.toInt().coerceIn(HistoryStore.MIN_INTERVAL_SEC, HistoryStore.MAX_INTERVAL_SEC)
            val slot = HistorySlot.of(p.id, g.id)
            for (k in g.chartAllKeys) {
                val ms = if (g.design.recordOnChange) maxOf(pollIntervals[k] ?: 0L, CHANGE_MIN_MS) else sec * 1000L
                out[RecordTarget(slot, k)] = RecordRule(ms, g.design.recordOnChange)
            }
        }
        return out
    }

    /** グラフごとの記録の保存期間 (ミリ秒) */
    fun retentions(pages: List<Dashboard>): Map<String, Long> =
        charts(pages).associate { (p, g) -> HistorySlot.of(p.id, g.id) to g.design.historyRetentionMs }

    /** グラフごとの使っている項目 (以前の共有の記録を振り分けるため) */
    fun chartKeys(pages: List<Dashboard>): Map<String, List<String>> =
        charts(pages).associate { (p, g) -> HistorySlot.of(p.id, g.id) to g.chartAllKeys }

    /**
     * 「変化したときだけ」の項目で、今回の値 [v] を記録するか。前回記録した値 [last] (時刻 [lastAt]) と違うか、
     * 変化が無いまま [CHANGE_HEARTBEAT_MS] が過ぎていれば記録する
     */
    fun shouldRecordChange(v: Double, last: Double?, lastAt: Long?, now: Long): Boolean =
        last == null || lastAt == null || v != last || now - lastAt >= CHANGE_HEARTBEAT_MS

    /**
     * 記録する項目 [due] を、表示用にすでに読んでいて値がある項目 (そのまま使う) と、記録のために読む項目に分ける
     */
    fun splitSample(due: Collection<String>, live: Map<String, Double>, activeKeys: Set<String>): Pair<Map<String, Double>, List<String>> {
        val reuse = due.filter { it in activeKeys && live[it] != null }.associateWith { live.getValue(it) }
        return reuse to due.filter { it !in reuse }
    }
}

/** 折れ線グラフの記録の置き場所 (グラフごと)。ページとメーターの id から作る */
object HistorySlot {
    fun of(pageId: Long, gaugeId: Long): String = "$pageId:$gaugeId"
}
