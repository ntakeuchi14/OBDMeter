package com.obdmeter.data

import com.obdmeter.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 値の記録をメモリーに持つ [HistoryStore] (Web 版用。ページを閉じると消える)。
 * 動きは Android 版のデータベース ([SqliteHistoryStore]) と同じにしてある
 */
class MemoryHistoryStore(
    /** 1 つの項目に持つ行の上限 (超えたら古い行から消す) */
    private val maxRowsPerSeries: Int = 200_000,
) : HistoryStore {

    private class Row(val t: Long, var v: Double, var c: Boolean)

    /** グラフ → 項目キー → 時刻順の行 */
    private val data = HashMap<String, HashMap<String, ArrayList<Row>>>()

    override var retentions: Map<String, Long>? = null

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision

    private var lastCleanup = 0L
    private val cleared = HashSet<String>()

    private fun series(g: String, k: String): ArrayList<Row>? = data[g]?.get(k)

    /** 時刻 [t] の行の位置 (無ければ -(入れる位置) - 1) */
    private fun ArrayList<Row>.find(t: Long): Int {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val mt = this[mid].t
            when {
                mt < t -> lo = mid + 1
                mt > t -> hi = mid - 1
                else -> return mid
            }
        }
        return -(lo + 1)
    }

    /** 1 つ目の t >= [t] の位置 */
    private fun ArrayList<Row>.lowerBound(t: Long): Int {
        val i = find(t)
        return if (i >= 0) i else -(i + 1)
    }

    private fun put(g: String, k: String, t: Long, v: Double, c: Boolean, replace: Boolean = true) {
        val list = data.getOrPut(g) { HashMap() }.getOrPut(k) { ArrayList() }
        val i = list.find(t)
        if (i >= 0) {
            if (replace) { list[i].v = v; list[i].c = c }
        } else {
            list.add(-(i + 1), Row(t, v, c))
            if (list.size > maxRowsPerSeries) list.subList(0, list.size - maxRowsPerSeries).clear()
        }
    }

    private fun changed() {
        _revision.value++
        val now = nowMs()
        if (now - lastCleanup > 600_000L) {
            lastCleanup = now
            cleanup(now)
        }
    }

    override suspend fun storeRows(rows: List<HistoryRow>) {
        if (rows.isEmpty()) return
        for (r in rows) {
            r.replaceT?.let { old ->
                if (old != r.t) series(r.slot, r.key)?.let { list -> list.find(old).let { if (it >= 0) list.removeAt(it) } }
            }
            put(r.slot, r.key, r.t / 1000 * 1000, r.v, r.cont)
        }
        changed()
    }

    override suspend fun lastRow(slot: String, key: String): Pair<HistoryRow, Boolean>? {
        val list = series(slot, key) ?: return null
        if (list.isEmpty()) return null
        val l = list.last()
        val last = HistoryRow(slot, key, l.t, l.v, l.c)
        val tail = last.cont && list.size >= 2 && list[list.size - 2].v == last.v
        return last to tail
    }

    override suspend fun clear(slot: String) {
        data.remove(slot)
        cleared += slot
        _revision.value++
    }

    override fun takeCleared(): Set<String> {
        if (cleared.isEmpty()) return emptySet()
        val out = HashSet(cleared)
        cleared.clear()
        return out
    }

    override suspend fun storeEvents(rows: List<HistoryRow>) {
        if (rows.isEmpty()) return
        for (r in rows) put(r.slot, r.key, r.t, r.v, false)
        changed()
    }

    override suspend fun monitorStats(slot: String, keys: List<String>, now: Long): List<Monitor.Stat> =
        keys.map { k ->
            val list = series(slot, k).orEmpty()
            val first = list.firstOrNull()
            val last = list.lastOrNull()
            val counts = if (first == null) Monitor.WINDOWS.map { 0 } else {
                // 初回の計測 (最初の行) は変化に数えない
                Monitor.WINDOWS.map { w ->
                    val from = now - w.first
                    list.count { it.t > first.t && it.t >= from }
                }
            }
            Monitor.Stat(k, first?.v, last?.v, last?.t?.takeIf { first != null && it != first.t }, counts)
        }

    override suspend fun store(rows: Map<String, Map<String, Double>>, time: Long) {
        if (rows.isEmpty()) return
        val t = time / 1000 * 1000
        for ((g, values) in rows) for ((k, v) in values) {
            if (v.isNaN() || v.isInfinite()) continue
            put(g, k, t, v, false)
        }
        changed()
    }

    override suspend fun load(slot: String, key: String, from: Long, to: Long, maxPoints: Int): List<Sample> {
        val list = series(slot, key) ?: return emptyList()
        val out = ArrayList<Sample>()
        val bucket = ((to - from) / (maxPoints / 2).coerceAtLeast(1)).coerceAtLeast(1000L)
        var i = list.lowerBound(from)
        while (i < list.size && list[i].t <= to) {
            val b = (list[i].t - from) / bucket
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            val t0 = list[i].t
            var t1 = t0
            var n = 0
            while (i < list.size && list[i].t <= to && (list[i].t - from) / bucket == b) {
                val r = list[i]
                if (r.v < lo) lo = r.v
                if (r.v > hi) hi = r.v
                t1 = r.t
                n++
                i++
            }
            val flo = lo.toFloat()
            val fhi = hi.toFloat()
            if (n == 1 || flo == fhi) out += Sample(t0, flo)
            else { out += Sample(t0, flo); out += Sample(t1, fhi) }
        }
        return out
    }

    override suspend fun neighbors(slot: String, key: String, from: Long, to: Long): Pair<Sample?, Sample?> {
        val list = series(slot, key) ?: return null to null
        val a = list.lowerBound(from) - 1
        var b = list.lowerBound(to)
        if (b < list.size && list[b].t == to) b++
        return list.getOrNull(a)?.let { Sample(it.t, it.v.toFloat()) } to
            list.getOrNull(b)?.let { Sample(it.t, it.v.toFloat()) }
    }

    override suspend fun segments(slot: String, keys: Collection<String>, from: Long, to: Long, gapMs: Long): List<Pair<Long, Long>> {
        if (keys.isEmpty()) return emptyList()
        val bucket = (gapMs / 2).coerceAtLeast(1000L)
        val ranges = HashMap<Long, LongArray>()
        val extra = ArrayList<Pair<Long, Long>>()
        for (k in keys) {
            val list = series(slot, k) ?: continue
            var i = list.lowerBound(from)
            while (i < list.size && list[i].t <= to) {
                val t = list[i].t
                val r = ranges.getOrPut(t / bucket) { longArrayOf(t, t) }
                if (t < r[0]) r[0] = t
                if (t > r[1]) r[1] = t
                i++
            }
            // 途切れずに値を見ていた行 (c = 1) は、1 つ前の行との間も記録のある時間に含める
            var j = list.lowerBound(from)
            while (j < list.size) {
                val r = list[j]
                if (r.c && j > 0) {
                    val a = maxOf(list[j - 1].t, from)
                    val b = minOf(r.t, to)
                    if (a <= b) extra += a to b
                }
                j++
            }
        }
        val buckets = ranges.entries.sortedBy { it.key }.map { it.value[0] to it.value[1] } + extra
        return HistoryCsv.mergeSegments(buckets.sortedBy { it.first }, gapMs)
    }

    override suspend fun earliest(slot: String, keys: Collection<String>): Long? =
        keys.mapNotNull { series(slot, it)?.firstOrNull()?.t }.minOrNull()

    override suspend fun exportCsvText(slot: String, keys: Collection<String>): Pair<Int, String> {
        val fmt = HistoryCsv.dateFormat()
        val sb = StringBuilder()
        sb.append(HistoryStore.CSV_HEADER).append('\n')
        var n = 0
        for (k in keys.toSortedSet()) {
            for (r in series(slot, k).orEmpty()) {
                sb.append(HistoryCsv.line(k, r.t, r.v, fmt)).append('\n')
                n++
            }
        }
        return n to sb.toString()
    }

    override suspend fun importCsvText(slot: String, text: String): Pair<Int, Int> {
        val formats = HistoryCsv.importFormats()
        var ok = 0
        var bad = 0
        for ((i, raw) in text.lineSequence().withIndex()) {
            val line = raw.removePrefix("﻿").trim()
            if (line.isEmpty()) continue
            if (i == 0 && HistoryCsv.isHeader(line)) continue
            val row = HistoryCsv.parseLine(line, formats)
            if (row == null) { bad++; continue }
            put(slot, row.first, row.second / 1000 * 1000, row.third, false)
            ok++
        }
        _revision.value++
        return ok to bad
    }

    override suspend fun adoptShared(slots: Map<String, Collection<String>>) {
        val shared = data[""] ?: return
        for ((g, keys) in slots) for (k in keys) {
            for (r in shared[k].orEmpty()) put(g, k, r.t, r.v, false, replace = false)
        }
        data.remove("")
        _revision.value++
    }

    private fun cleanup(now: Long) {
        val slots = retentions ?: return
        for ((g, ms) in slots) {
            if (ms <= 0) continue // 変動監視: メーターを削除するまで残す
            val keep = ms.coerceIn(3_600_000L, HistoryStore.MAX_MONTHS * HistoryStore.MONTH_MS)
            val limit = now - keep
            data[g]?.values?.forEach { list ->
                val n = list.lowerBound(limit)
                if (n > 0) list.subList(0, n).clear()
            }
        }
        data.keys.retainAll { it == "" || it in slots.keys }
    }
}
