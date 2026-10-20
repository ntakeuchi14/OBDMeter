package com.obdmeter.data

/**
 * 記録に書く 1 行。[cont] = 同じグラフ・項目の 1 つ前の行から途切れずに値を見ていた
 * (間に記録が無くても、その間は値が変わらなかっただけ)。[replaceT] があれば、その時刻の行を消してから書く
 */
data class HistoryRow(
    val slot: String,
    val key: String,
    val t: Long,
    val v: Double,
    val cont: Boolean,
    val replaceT: Long? = null,
)

/**
 * 「変化したときだけ記録する」グラフの記録の決め方 (データベースは使わない)。
 *
 * 値が変わったときに 1 行書き、変わらない間は「その値を最後に見た時刻」の行 (末尾の行) を 1 つだけ持って
 * 時刻を新しくしていく。値が続いていた区間は「変化した時刻の行」〜「末尾の行」の 2 行で表せるので、
 * 線は途切れず、余計な行も増えない。末尾の行の書き換えは [TAIL_FLUSH_MS] ごと
 * (値が変わったとき・途切れたときは、その前に最後に見た時刻まで書く)。
 *
 * 値を見る間隔の 3 倍 (最低 [MIN_GAP_MS]) より長く見ていなかったら、途切れた (接続が切れた・表示していなかった) とみなす。
 * 時刻はすべて秒単位に丸める
 */
class ChangeRecorder {
    companion object {
        const val TAIL_FLUSH_MS = 5_000L
        const val MIN_GAP_MS = 5_000L
    }

    private class State(
        var value: Double,
        /** 最後に値を見た時刻 */
        var seen: Long,
        /** 記録に書いた最後の行の時刻 */
        var stored: Long,
        /** 最後の行が動かせる末尾の行なら、その時刻 */
        var tail: Long?,
    )

    private val states = HashMap<ReadPlan.RecordTarget, State>()

    private fun sec(t: Long) = t / 1000 * 1000

    /** 最後に見た時刻まで末尾の行を書く */
    private fun flush(target: ReadPlan.RecordTarget, s: State, out: MutableList<HistoryRow>) {
        if (sec(s.seen) <= sec(s.stored)) return
        out += HistoryRow(target.slot, target.key, sec(s.seen), s.value, cont = true, replaceT = s.tail)
        s.stored = s.seen
        s.tail = sec(s.seen)
    }

    /**
     * 値 [v] を時刻 [time] に見た。値を見る間隔は [intervalMs]。記録に書く行を返す (空なら書くものなし)
     */
    fun observe(target: ReadPlan.RecordTarget, v: Double, time: Long, intervalMs: Long): List<HistoryRow> {
        if (v.isNaN() || v.isInfinite()) return emptyList()
        val out = ArrayList<HistoryRow>(2)
        val s = states[target]
        val gap = maxOf(intervalMs * 3, MIN_GAP_MS)
        when {
            s == null || time - s.seen > gap -> {
                // 初めて・途切れたあと: 前の続きを書き切ってから、新しい区間を始める
                if (s != null) flush(target, s, out)
                out += HistoryRow(target.slot, target.key, sec(time), v, cont = false)
                states[target] = State(v, time, time, null)
            }
            v != s.value -> {
                // 変わった: 前の値を最後に見た時刻まで書いてから、新しい値を書く
                flush(target, s, out)
                out += HistoryRow(target.slot, target.key, sec(time), v, cont = true)
                s.value = v; s.seen = time; s.stored = time; s.tail = null
            }
            else -> {
                s.seen = time
                if (time - s.stored >= TAIL_FLUSH_MS) flush(target, s, out)
            }
        }
        return out
    }

    /** 記録しなくなった項目の状態を捨てる (残っている末尾を書く行を返す) */
    fun retain(targets: Set<ReadPlan.RecordTarget>): List<HistoryRow> {
        val out = ArrayList<HistoryRow>()
        val it = states.entries.iterator()
        while (it.hasNext()) {
            val (t, s) = it.next()
            if (t !in targets) { flush(t, s, out); it.remove() }
        }
        return out
    }
}
