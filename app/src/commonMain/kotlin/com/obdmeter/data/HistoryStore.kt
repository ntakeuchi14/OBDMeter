package com.obdmeter.data

import kotlinx.coroutines.flow.StateFlow

/**
 * 折れ線グラフ・変動監視の値の記録。メーターごと ([HistorySlot])・項目ごとに「時刻・値」を持つ。
 * Android はデータベース ([SqliteHistoryStore])、Web はメモリーに持つ
 */
interface HistoryStore {
    companion object {
        const val MIN_INTERVAL_SEC = 1
        const val MAX_INTERVAL_SEC = 600
        const val MAX_MONTHS = 24
        const val MONTH_MS = 2_629_746_000L // 1 か月 (平均 30.44 日)
        const val CSV_HEADER = HistoryCsv.HEADER
    }

    /** 今あるメーターと、それぞれの保存期間 (ミリ秒)。null = まだ分からない (何も消さない) */
    var retentions: Map<String, Long>?

    /** 記録が増えるたびに変わる (グラフの読み直しに使う) */
    val revision: StateFlow<Long>

    /** 「変化したときだけ記録する」グラフの行を書く */
    suspend fun storeRows(rows: List<HistoryRow>)

    /** メーター [slot] の項目 [key] の最後の行と、それが動かせる末尾の行か */
    suspend fun lastRow(slot: String, key: String): Pair<HistoryRow, Boolean>?

    /** メーター [slot] の記録をすべて消す (リセット。変動時の前後の値のファイルも消す) */
    suspend fun clear(slot: String)

    /** 変動監視: 変動したときの前後の値のファイル ([MonitorCapture]) を保存する */
    suspend fun saveCapture(slot: String, name: String, text: String)

    /** 変動監視: 保存してある前後の値のファイルの名前 (古い順) */
    suspend fun captureNames(slot: String): List<String>

    /** 変動監視: 保存してある前後の値のファイル (名前, 内容。古い順) */
    suspend fun captures(slot: String): List<Pair<String, String>>

    /** [clear] で記録を消したメーター (受け取ったら空になる) */
    fun takeCleared(): Set<String>

    /** 変動監視の行を書く (時刻は丸めない) */
    suspend fun storeEvents(rows: List<HistoryRow>)

    /** 変動監視の集計。[keys] の順に返す */
    suspend fun monitorStats(slot: String, keys: List<String>, now: Long): List<Monitor.Stat>

    /** 値を記録する ([rows] はメーター → 項目キー → 値)。時刻は秒単位に丸める */
    suspend fun store(rows: Map<String, Map<String, Double>>, time: Long)

    /** [from]〜[to] の記録を読む。点が多いときは [maxPoints] 程度にまとめる (区間ごとの最小と最大を残す) */
    suspend fun load(slot: String, key: String, from: Long, to: Long, maxPoints: Int): List<Sample>

    /** 直前と直後の記録 (表示範囲の外とも線をつなぐため) */
    suspend fun neighbors(slot: String, key: String, from: Long, to: Long): Pair<Sample?, Sample?>

    /** [from]〜[to] で、[keys] のどれかに記録がある時間帯 (開始, 終了) を古い順に返す */
    suspend fun segments(slot: String, keys: Collection<String>, from: Long, to: Long, gapMs: Long): List<Pair<Long, Long>>

    /** [keys] の記録の一番古い時刻 (無ければ null) */
    suspend fun earliest(slot: String, keys: Collection<String>): Long?

    /** [keys] の記録を CSV (key,datetime,value) の文字列にする。行数と内容を返す */
    suspend fun exportCsvText(slot: String, keys: Collection<String>): Pair<Int, String>

    /** CSV をメーター [slot] の記録として読み込む。読み込んだ行数と、読めなかった行数を返す */
    suspend fun importCsvText(slot: String, text: String): Pair<Int, Int>

    /** 以前の版で項目ごとに共有していた記録を、その項目を使っているグラフそれぞれへ写して消す */
    suspend fun adoptShared(slots: Map<String, Collection<String>>)
}
