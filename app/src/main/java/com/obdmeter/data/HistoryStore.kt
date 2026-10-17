package com.obdmeter.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 記録した値 1 つ (時刻はミリ秒) */
class Sample(val t: Long, val v: Float)

/**
 * 折れ線グラフ用の値の記録。
 * - 直近 [MEMORY_MS] はメモリに細かく (最短 [MEMORY_STEP_MS] 間隔) 持ち、ライブ表示に使う
 * - それより前は端末のデータベースに 1 秒ごとに保存し、さかのぼって表示するときに読む
 * - 保存期間 ([retentionDays]) を過ぎた記録は 1 時間ごとに消す
 * 記録するのは [keys] の項目 (どこかの折れ線グラフで使われている項目) だけ。
 */
class HistoryStore(context: Context, private val scope: CoroutineScope) {

    companion object {
        const val MEMORY_MS = 10 * 60_000L
        const val MEMORY_STEP_MS = 200L
        const val DB_STEP_MS = 1_000L
        const val MAX_DAYS = 30
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE samples (k TEXT NOT NULL, t INTEGER NOT NULL, v REAL NOT NULL)")
            db.execSQL("CREATE INDEX samples_kt ON samples (k, t)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val helper = Helper(context.applicationContext)
    private val db: SQLiteDatabase by lazy { helper.writableDatabase }

    /** 記録する項目のキー */
    @Volatile var keys: Set<String> = emptySet()
    /** 保存期間 (日) */
    @Volatile var retentionDays: Int = 1

    private val lock = Any()
    private val recent = HashMap<String, ArrayDeque<Sample>>()
    private val lastMem = HashMap<String, Long>()
    private val lastDb = HashMap<String, Long>()
    private val pending = ArrayList<Triple<String, Long, Float>>()

    init {
        scope.launch(Dispatchers.IO) {
            var lastCleanup = 0L
            while (true) {
                delay(5_000)
                runCatching { flush() }
                val now = System.currentTimeMillis()
                if (now - lastCleanup > 3_600_000L) {
                    lastCleanup = now
                    runCatching { cleanup(now) }
                }
            }
        }
    }

    /** 最新の値を記録する (値が届くたびに呼ぶ。間引きはここで行う) */
    fun record(values: Map<String, Double>, now: Long = System.currentTimeMillis()) {
        val ks = keys
        if (ks.isEmpty()) return
        synchronized(lock) {
            for (k in ks) {
                val v = values[k] ?: continue
                if (v.isNaN() || v.isInfinite()) continue
                if (now - (lastMem[k] ?: 0L) >= MEMORY_STEP_MS) {
                    lastMem[k] = now
                    val q = recent.getOrPut(k) { ArrayDeque() }
                    q.addLast(Sample(now, v.toFloat()))
                    while (q.isNotEmpty() && q.first().t < now - MEMORY_MS) q.removeFirst()
                }
                if (now - (lastDb[k] ?: 0L) >= DB_STEP_MS) {
                    lastDb[k] = now
                    pending += Triple(k, now, v.toFloat())
                }
            }
        }
    }

    /** メモリにある、[from] 以降の記録 */
    fun recent(key: String, from: Long): List<Sample> = synchronized(lock) {
        val q = recent[key] ?: return emptyList()
        // 古い順に並んでいるので、from 以降の位置を二分探索で探す
        var lo = 0
        var hi = q.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (q[mid].t < from) lo = mid + 1 else hi = mid
        }
        ArrayList(q.subList(lo, q.size))
    }

    /** メモリに持っている一番古い時刻 (無ければ Long.MAX_VALUE) */
    fun memoryStart(key: String): Long = synchronized(lock) { recent[key]?.firstOrNull()?.t ?: Long.MAX_VALUE }

    /**
     * データベースから [from]〜[to] の記録を読む。点が多いときは [maxPoints] 程度に間引く
     * (区間ごとの最小と最大を残すので、山や谷は消えない)
     */
    suspend fun load(key: String, from: Long, to: Long, maxPoints: Int): List<Sample> = withContext(Dispatchers.IO) {
        runCatching { flush() }
        val out = ArrayList<Sample>()
        runCatching {
            db.rawQuery(
                "SELECT t, v FROM samples WHERE k = ? AND t >= ? AND t <= ? ORDER BY t",
                arrayOf(key, from.toString(), to.toString()),
            ).use { c -> while (c.moveToNext()) out += Sample(c.getLong(0), c.getFloat(1)) }
        }
        decimate(out, from, to, maxPoints)
    }

    private fun decimate(points: List<Sample>, from: Long, to: Long, maxPoints: Int): List<Sample> {
        if (points.size <= maxPoints || maxPoints < 4) return points
        val buckets = maxPoints / 2
        val width = ((to - from).toDouble() / buckets).coerceAtLeast(1.0)
        val out = ArrayList<Sample>(maxPoints + 2)
        var i = 0
        while (i < points.size) {
            val b = ((points[i].t - from) / width).toLong()
            var lo = points[i]
            var hi = points[i]
            var j = i
            while (j < points.size && ((points[j].t - from) / width).toLong() == b) {
                if (points[j].v < lo.v) lo = points[j]
                if (points[j].v > hi.v) hi = points[j]
                j++
            }
            if (lo === hi) out += lo
            else if (lo.t <= hi.t) { out += lo; out += hi } else { out += hi; out += lo }
            i = j
        }
        return out
    }

    private fun flush() {
        val batch = synchronized(lock) {
            if (pending.isEmpty()) return
            ArrayList(pending).also { pending.clear() }
        }
        db.beginTransaction()
        try {
            val cv = ContentValues()
            for ((k, t, v) in batch) {
                cv.clear()
                cv.put("k", k); cv.put("t", t); cv.put("v", v)
                db.insert("samples", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun cleanup(now: Long) {
        val days = retentionDays.coerceIn(1, MAX_DAYS)
        db.delete("samples", "t < ?", arrayOf((now - days * 86_400_000L).toString()))
    }

    /** 記録をすべて消す */
    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            recent.clear(); lastMem.clear(); lastDb.clear(); pending.clear()
        }
        runCatching { db.delete("samples", null, null) }
        Unit
    }
}
