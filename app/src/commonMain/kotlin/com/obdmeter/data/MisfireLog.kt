package com.obdmeter.data

import com.obdmeter.core.DeviceItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 失火履歴 (項目 [DeviceItems.MISFIRE_LOG]): 気筒 1〜6 の失火カウンタを見て、増えた気筒と時刻を残す。
 * 変化は接続ごと: 接続した後の最初の値を基準にし (最初の値は変化ではない)、切断して再接続したら履歴も基準も捨てる
 */
object MisfireLog {
    /** 気筒 [cyl] の失火カウンタが [time] に [delta] 回増えた */
    data class Event(val cyl: Int, val time: Long, val delta: Int)

    /** 監視するカウンタ */
    enum class Source(val keys: List<String>) {
        /** OBD Mode 06 の今回の運転の失火カウンタ (失火#1〜#6) */
        OBD((1..6).map { "MISFIRE_$it" }),
        /** BMW DME の生涯失火数 (生涯失火#1〜#6) */
        BMW((1..6).map { "MISFIRE_LIFE_$it" }),
    }

    /** 残しておく件数 (表示する件数より多めに) */
    const val MAX_EVENTS = 50
    /** 表示する件数の上限 */
    const val MAX_SHOWN = 20

    /** メーターの項目からどのカウンタを見るか */
    fun sourceOf(pidKey: String): Source =
        if (pidKey == DeviceItems.MISFIRE_LOG_LIFE || pidKey.startsWith("MISFIRE_LIFE_")) Source.BMW else Source.OBD

    fun keysFor(pidKey: String): List<String> = sourceOf(pidKey).keys

    /** 項目の値 (今の接続で増えた件数。残しておく件数 [MAX_EVENTS] を超えても数える) */
    fun values(counts: Map<Source, Int>): Map<String, Double> = mapOf(
        DeviceItems.MISFIRE_LOG to (counts[Source.OBD] ?: 0).toDouble(),
        DeviceItems.MISFIRE_LOG_LIFE to (counts[Source.BMW] ?: 0).toDouble(),
    )

    /** 1 つのカウンタの組 (6 気筒) の変化を見る */
    class Tracker(private val source: Source) {
        private val base = HashMap<Int, Double>()
        private val list = ArrayList<Event>()
        val events: List<Event> get() = list
        /** 今の接続で増えた件数 (残しておく件数を超えても数える) */
        var total = 0
            private set

        fun reset() { base.clear(); list.clear(); total = 0 }

        /** 読んだ値 (項目キー → 値) を見て、増えた気筒を記録する。記録したら true */
        fun observe(values: Map<String, Double>, time: Long): Boolean {
            var changed = false
            source.keys.forEachIndexed { i, key ->
                val v = values[key] ?: return@forEachIndexed
                if (v.isNaN()) return@forEachIndexed
                val cyl = i + 1
                val prev = base[cyl]
                base[cyl] = v
                // 最初の値は基準。減ったとき (運転サイクルが変わってカウンタが 0 に戻った など) は基準を更新するだけ
                if (prev != null && v > prev) {
                    val d = kotlin.math.ceil(v - prev - 1e-9).toInt().coerceAtLeast(1)
                    list.add(0, Event(cyl, time, d))
                    total++
                    changed = true
                }
            }
            while (list.size > MAX_EVENTS) list.removeAt(list.lastIndex)
            return changed
        }
    }

    private val _events = MutableStateFlow<Map<Source, List<Event>>>(emptyMap())
    /** 今の接続での変化 (新しい順) */
    val events: StateFlow<Map<Source, List<Event>>> = _events

    private val _counts = MutableStateFlow<Map<Source, Int>>(emptyMap())
    /** 今の接続で増えた件数 */
    val counts: StateFlow<Map<Source, Int>> = _counts

    private val trackers = Source.entries.associateWith { Tracker(it) }

    /** 接続し直したとき: 履歴も基準も捨てる */
    fun resetAll() {
        trackers.values.forEach { it.reset() }
        _events.value = emptyMap()
        _counts.value = emptyMap()
    }

    fun observe(source: Source, values: Map<String, Double>, time: Long) {
        val t = trackers.getValue(source)
        if (t.observe(values, time)) {
            _events.value = _events.value + (source to t.events.toList())
            _counts.value = _counts.value + (source to t.total)
        }
    }

    /** 表示用の 1 行 ("#3 12:34:56"、2 回以上増えていれば " +2") */
    fun format(e: Event): String =
        "#${e.cyl} ${DeviceItems.formatTime(DeviceItems.jstSecondsOfDay(e.time))}" + if (e.delta > 1) " +${e.delta}" else ""
}
