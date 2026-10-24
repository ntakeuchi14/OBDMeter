package com.obdmeter.core

/**
 * 1 サイクルで何をどう読むかを決める計算 (通信はしない)。[ObdReader] が使う。
 */
object PollPlan {
    /** 表示用の測定値ブロックの最初の番号 (F0)。F0〜F8 を取得周期ごとに使う */
    const val LID_FIRST = 0xF0
    /** グラフ記録用の 1 回読みで使う番号 (表示用の登録を崩さないよう別にする) */
    const val LID_SAMPLE = 0xF9
    /** 表示用に使える番号の数 (F0〜F8) */
    const val MAX_GROUPS = 9

    /** 宛先の並び順のキー。標準 (宛先指定なし) → ヘッダー指定 → 拡張アドレス (BMW 方式) の順 */
    fun addressKey(d: PidDef): String {
        val r = d.request ?: return ""
        if (r.header.isEmpty() && r.canExtAddress.isEmpty()) return ""
        return (if (r.canExtAddress.isEmpty()) "1/" else "2/") + r.header + "/" + r.canExtAddress
    }

    /**
     * 項目ごとの取得周期を、このサイクルで読む項目に当てはめたもの。
     * 派生項目 (ブーストなど) に周期を設定してあれば、その依存先にも当てる
     * (依存先に自分の設定があればそちらを優先。複数の派生項目から当たるときは短いほう)
     */
    fun effectiveIntervals(defs: Collection<PidDef>, intervals: Map<String, Long>): Map<String, Long> {
        if (intervals.isEmpty()) return emptyMap()
        val m = HashMap(intervals)
        for (d in defs) {
            if (d.kind != PidDef.Kind.DERIVED) continue
            val iv = intervals[d.key] ?: continue
            for (dep in d.deps) if (dep !in intervals) m[dep] = minOf(m[dep] ?: Long.MAX_VALUE, iv)
        }
        return m
    }

    /**
     * BMW の測定値を取得周期ごとのまとまりに分け、番号 (F0, F1, …) を割り当てる。周期の短い順。
     * 番号が足りないとき (周期が 10 種類以上) は、長い周期のものを最後のまとまりにまとめる
     */
    fun blockGroups(block: List<PidDef>, intervals: Map<String, Long>): List<Pair<Int, List<PidDef>>> {
        var groups = block.groupBy { intervals[it.key] ?: 0L }.entries.sortedBy { it.key }.map { it.value }
        if (groups.size > MAX_GROUPS) groups = groups.take(MAX_GROUPS - 1) + listOf(groups.drop(MAX_GROUPS - 1).flatten())
        return groups.mapIndexed { i, g -> (LID_FIRST + i) to g }
    }
}
