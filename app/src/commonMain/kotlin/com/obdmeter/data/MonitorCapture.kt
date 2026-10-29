package com.obdmeter.data

import com.obdmeter.util.DateFmt

/**
 * 変動監視: 変動を検知したときの前後の値 (選んだ項目) をファイル (CSV) にする。
 *
 * 監視の読み出し 1 回 = 1 カウント。メーターごとに直前 [COUNT] 回分の値を持っておき、
 * 変動を検知したら、その後さらに [COUNT] 回読み終えたところで 1 ファイルにする
 * (途中で通信が切れたら、そこまでの分で書き出す)。通信やデータベースは使わない
 */
class MonitorCapture(private val formatValue: (key: String, v: Double) -> String = { _, v -> v.toString() }) {
    companion object {
        /** 前後それぞれのカウント数 */
        const val COUNT = 5
        /** 選べる項目の上限 */
        const val MAX_KEYS = 30
        private const val HEADER_OFFSET = "相対"
        private const val HEADER_TIME = "日時"

        /** ファイル名に使えない文字を _ にする */
        fun safeName(s: String): String = s.map { if (it in "\\/:*?\"<>|\r\n\t") '_' else it }.joinToString("")

        /** ファイル名 (計測項目-変動検知時間-そのときの値.csv) */
        fun fileName(key: String, time: Long, value: String): String =
            safeName("$key-${DateFmt("yyyyMMdd_HHmmss").format(time)}-$value") + ".csv"

        private fun cell(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    /** 1 回の読み出しの値 */
    class Snapshot(val time: Long, val values: Map<String, Double>)

    /** 書き出すファイル */
    data class File(val slot: String, val name: String, val text: String)

    private class Pending(
        val slot: String, val key: String, val time: Long, val value: Double, val columns: List<String>,
        val rows: MutableList<Pair<Int, Snapshot>>,
    ) {
        var after = 0
    }

    private val rings = HashMap<String, ArrayDeque<Snapshot>>()
    private val pending = ArrayList<Pending>()

    /**
     * メーター [slot] の 1 回分の読み出し [snap] を渡す。[events] = この回で検知した変動 (項目キー, 値)、
     * [captureKeys] = 前後の値を残す項目。書き終えたファイルを返す
     */
    fun observe(slot: String, snap: Snapshot, events: List<Pair<String, Double>>, captureKeys: List<String>): List<File> {
        val out = ArrayList<File>()
        // 先に検知済みのものへ「後」の行として足す
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.slot != slot) continue
            p.after++
            p.rows += p.after to snap
            if (p.after >= COUNT) { out += write(p); it.remove() }
        }
        val ring = rings.getOrPut(slot) { ArrayDeque() }
        for ((key, v) in events) {
            val before = ring.toList()
            val rows = ArrayList<Pair<Int, Snapshot>>()
            before.forEachIndexed { i, s -> rows += (i - before.size) to s }
            rows += 0 to snap
            pending += Pending(slot, key, snap.time, v, (listOf(key) + captureKeys).distinct(), rows)
        }
        ring.addLast(snap)
        while (ring.size > COUNT) ring.removeFirst()
        return out
    }

    /** 読み終わる前に通信が切れたなど: 途中のものをそこまでの分で書き出す */
    fun flush(): List<File> {
        val out = pending.map(::write)
        pending.clear()
        rings.clear()
        return out
    }

    /** メーター [slot] の途中のものと直前の値を捨てる (リセット) */
    fun forget(slot: String) {
        pending.removeAll { it.slot == slot }
        rings.remove(slot)
    }

    /** [slots] 以外 (前後の値を残さなくなったメーター) のものを捨てる */
    fun retain(slots: Set<String>) {
        pending.removeAll { it.slot !in slots }
        rings.keys.retainAll(slots)
    }

    private fun write(p: Pending): File {
        val fmt = DateFmt("yyyy/MM/dd HH:mm:ss.SSS")
        val sb = StringBuilder()
        sb.append((listOf(HEADER_OFFSET, HEADER_TIME) + p.columns).joinToString(",") { cell(it) }).append('\n')
        for ((offset, s) in p.rows) {
            sb.append(offset).append(',').append(fmt.format(s.time))
            for (k in p.columns) {
                sb.append(',')
                s.values[k]?.let { sb.append(cell(formatValue(k, it))) }
            }
            sb.append('\n')
        }
        return File(p.slot, fileName(p.key, p.time, formatValue(p.key, p.value)), sb.toString())
    }
}
