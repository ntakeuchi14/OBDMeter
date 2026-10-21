package com.obdmeter.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * 折れ線グラフ用の値の記録。グラフごと ([HistorySlot]) ・項目ごとに「時刻 (秒単位)・値」を端末のデータベースに保存する。
 * 同じ項目でもグラフが違えば別々に記録する (グラフを新しく置くと、記録は空から始まる)。
 * - 何をいつ記録するかは呼び出し側 ([com.obdmeter.AppCore]) が決める
 * - グラフごとの保存期間 ([retentions]) を過ぎた記録と、無くなったグラフの記録は自動で消す
 * - CSV (項目キー, 年月日時分秒, 値) で書き出し・読み込みができる
 */
class HistoryStore(context: Context, private val scope: CoroutineScope) {

    companion object {
        const val MIN_INTERVAL_SEC = 1
        const val MAX_INTERVAL_SEC = 600
        const val MAX_MONTHS = 24
        const val MONTH_MS = 2_629_746_000L // 1 か月 (平均 30.44 日)
        const val CSV_HEADER = HistoryCsv.HEADER
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "history.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            // 同じグラフ・同じ項目・同じ時刻の記録は 1 つだけ (読み込みで重ねても増えない)
            // c = 1: 1 つ前の行から途切れずに値を見ていた (変化したときだけ記録するグラフで、間は同じ値が続いていた)
            db.execSQL(
                "CREATE TABLE samples (g TEXT NOT NULL, k TEXT NOT NULL, t INTEGER NOT NULL, v REAL NOT NULL, " +
                    "c INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (g, k, t)) WITHOUT ROWID"
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 3) {
                db.execSQL("ALTER TABLE samples ADD COLUMN c INTEGER NOT NULL DEFAULT 0")
            } else if (oldVersion == 2) {
                // 項目ごとに共有していた記録は、いったんグラフ未定 ("") として残し、
                // 起動後にその項目を使っているグラフへ振り分ける ([adoptShared])
                db.execSQL("ALTER TABLE samples RENAME TO samples_old")
                onCreate(db)
                db.execSQL("INSERT INTO samples (g, k, t, v) SELECT '', k, t, v FROM samples_old")
                db.execSQL("DROP TABLE samples_old")
            } else {
                // 旧形式 (1 秒ごとの細かい記録) は引き継がない
                db.execSQL("DROP TABLE IF EXISTS samples")
                onCreate(db)
            }
        }
    }

    private val helper = Helper(context.applicationContext)
    private val db: SQLiteDatabase by lazy { helper.writableDatabase }

    /**
     * 今あるグラフと、それぞれの保存期間 (ミリ秒)。null = まだ分からない (起動直後は何も消さない)。
     * ここに無いグラフの記録は、片付けのときに消す
     */
    @Volatile var retentions: Map<String, Long>? = null

    private val _revision = MutableStateFlow(0L)
    /** 記録が増えるたびに変わる (グラフの読み直しに使う) */
    val revision: StateFlow<Long> = _revision

    init {
        scope.launch(Dispatchers.IO) {
            // 保存期間の設定が読み込まれるのを待ってから消す
            delay(60_000L)
            while (true) {
                runCatching { cleanup(System.currentTimeMillis()) }
                delay(600_000L)
            }
        }
    }

    /** 「変化したときだけ記録する」グラフの行 ([ChangeRecorder] が決めたもの) を書く */
    suspend fun storeRows(rows: List<HistoryRow>) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        runCatching {
            db.beginTransaction()
            try {
                val cv = ContentValues()
                for (r in rows) {
                    r.replaceT?.let { old ->
                        if (old != r.t) db.delete("samples", "g = ? AND k = ? AND t = ?", arrayOf(r.slot, r.key, old.toString()))
                    }
                    cv.clear()
                    cv.put("g", r.slot); cv.put("k", r.key); cv.put("t", r.t / 1000 * 1000); cv.put("v", r.v)
                    cv.put("c", if (r.cont) 1 else 0)
                    db.insertWithOnConflict("samples", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        _revision.value++
    }

    /**
     * グラフ [slot] の項目 [key] の最後の行と、それが動かせる末尾の行 (1 つ前の行と同じ値で、途切れずに見ていた行) か
     */
    suspend fun lastRow(slot: String, key: String): Pair<HistoryRow, Boolean>? = withContext(Dispatchers.IO) {
        runCatching {
            db.rawQuery(
                "SELECT t, v, c FROM samples WHERE g = ? AND k = ? ORDER BY t DESC LIMIT 2", arrayOf(slot, key),
            ).use { c ->
                if (!c.moveToFirst()) return@use null
                val last = HistoryRow(slot, key, c.getLong(0), c.getDouble(1), c.getInt(2) == 1)
                val tail = last.cont && c.moveToNext() && c.getDouble(1) == last.v
                last to tail
            }
        }.getOrNull()
    }

    /** 変動監視の行を書く (時刻は丸めない: 1 秒に何度変わっても回数を数えられるように) */
    suspend fun storeEvents(rows: List<HistoryRow>) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        runCatching {
            db.beginTransaction()
            try {
                val cv = ContentValues()
                for (r in rows) {
                    cv.clear()
                    cv.put("g", r.slot); cv.put("k", r.key); cv.put("t", r.t); cv.put("v", r.v); cv.put("c", 0)
                    db.insertWithOnConflict("samples", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        _revision.value++
    }

    /** 変動監視の集計 ([Monitor.Stat])。[keys] の順に返す */
    suspend fun monitorStats(slot: String, keys: List<String>, now: Long): List<Monitor.Stat> = withContext(Dispatchers.IO) {
        keys.map { k ->
            runCatching {
                fun one(order: String): Pair<Long, Double>? =
                    db.rawQuery("SELECT t, v FROM samples WHERE g = ? AND k = ? ORDER BY t $order LIMIT 1", arrayOf(slot, k))
                        .use { c -> if (c.moveToFirst()) c.getLong(0) to c.getDouble(1) else null }
                val first = one("ASC")
                val last = one("DESC")
                val counts = if (first == null) Monitor.WINDOWS.map { 0 } else {
                    // 初回の計測 (最初の行) は変化に数えない
                    val sums = Monitor.WINDOWS.joinToString(", ") { "SUM(t >= ?)" }
                    db.rawQuery(
                        "SELECT $sums FROM samples WHERE g = ? AND k = ? AND t > ?",
                        (Monitor.WINDOWS.map { (now - it.first).toString() } + listOf(slot, k, first.first.toString())).toTypedArray(),
                    ).use { c -> if (c.moveToFirst()) Monitor.WINDOWS.indices.map { c.getInt(it) } else Monitor.WINDOWS.map { 0 } }
                }
                Monitor.Stat(k, first?.second, last?.second, last?.first?.takeIf { first != null && it != first.first }, counts)
            }.getOrElse { Monitor.Stat(k, null, null, null, Monitor.WINDOWS.map { 0 }) }
        }
    }

    /** 値を記録する ([rows] はグラフ → 項目キー → 値)。時刻は秒単位に丸める */
    suspend fun store(rows: Map<String, Map<String, Double>>, time: Long) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val t = time / 1000 * 1000
        runCatching {
            db.beginTransaction()
            try {
                val cv = ContentValues()
                for ((g, values) in rows) for ((k, v) in values) {
                    if (v.isNaN() || v.isInfinite()) continue
                    cv.clear()
                    cv.put("g", g); cv.put("k", k); cv.put("t", t); cv.put("v", v)
                    db.insertWithOnConflict("samples", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        _revision.value++
    }

    /**
     * [from]〜[to] の記録を読む。点が多いときは [maxPoints] 程度にまとめる
     * (区間ごとの最小と最大を残すので、山や谷は消えない)
     */
    suspend fun load(slot: String, key: String, from: Long, to: Long, maxPoints: Int): List<Sample> = withContext(Dispatchers.IO) {
        val out = ArrayList<Sample>()
        val bucket = ((to - from) / (maxPoints / 2).coerceAtLeast(1)).coerceAtLeast(1000L)
        runCatching {
            db.rawQuery(
                "SELECT (t - ?) / ? AS b, MIN(v), MAX(v), MIN(t), MAX(t), COUNT(*) FROM samples " +
                    "WHERE g = ? AND k = ? AND t >= ? AND t <= ? GROUP BY b ORDER BY b",
                arrayOf(from.toString(), bucket.toString(), slot, key, from.toString(), to.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val lo = c.getFloat(1)
                    val hi = c.getFloat(2)
                    val t0 = c.getLong(3)
                    val t1 = c.getLong(4)
                    if (c.getInt(5) == 1 || lo == hi) out += Sample(t0, lo)
                    else { out += Sample(t0, lo); out += Sample(t1, hi) }
                }
            }
        }
        out
    }

    /** 直前と直後の記録 (表示範囲の外とも線をつなぐため) */
    suspend fun neighbors(slot: String, key: String, from: Long, to: Long): Pair<Sample?, Sample?> = withContext(Dispatchers.IO) {
        fun one(sql: String, arg: Long): Sample? = runCatching {
            db.rawQuery(sql, arrayOf(slot, key, arg.toString())).use { c -> if (c.moveToFirst()) Sample(c.getLong(0), c.getFloat(1)) else null }
        }.getOrNull()
        one("SELECT t, v FROM samples WHERE g = ? AND k = ? AND t < ? ORDER BY t DESC LIMIT 1", from) to
            one("SELECT t, v FROM samples WHERE g = ? AND k = ? AND t > ? ORDER BY t ASC LIMIT 1", to)
    }

    /**
     * [from]〜[to] で、[keys] のどれかに記録がある時間帯 (開始, 終了) を古い順に返す。
     * 記録の間が [gapMs] より空いたところで区切る (グラフで記録の無い時間を詰めるため)
     */
    suspend fun segments(slot: String, keys: Collection<String>, from: Long, to: Long, gapMs: Long): List<Pair<Long, Long>> =
        withContext(Dispatchers.IO) {
            if (keys.isEmpty()) return@withContext emptyList()
            val bucket = (gapMs / 2).coerceAtLeast(1000L)
            val out = ArrayList<Pair<Long, Long>>()
            runCatching {
                db.rawQuery(
                    "SELECT t / ? AS b, MIN(t), MAX(t) FROM samples WHERE g = ? AND k IN (${keys.joinToString(",") { "?" }}) " +
                        "AND t >= ? AND t <= ? GROUP BY b ORDER BY b",
                    arrayOf(bucket.toString(), slot) + keys.toTypedArray() + arrayOf(from.toString(), to.toString()),
                ).use { c ->
                    val buckets = ArrayList<Pair<Long, Long>>()
                    while (c.moveToNext()) buckets += c.getLong(1) to c.getLong(2)
                    // 途切れずに値を見ていた行 (c = 1) は、1 つ前の行との間も記録のある時間に含める
                    // (変化したときだけ記録するグラフで、同じ値が続いて行が離れていても詰めない)
                    db.rawQuery(
                        "SELECT (SELECT MAX(p.t) FROM samples p WHERE p.g = s.g AND p.k = s.k AND p.t < s.t), s.t " +
                            "FROM samples s WHERE s.g = ? AND s.k IN (${keys.joinToString(",") { "?" }}) AND s.c = 1 AND s.t >= ?",
                        arrayOf(slot) + keys.toTypedArray() + arrayOf(from.toString()),
                    ).use { r ->
                        while (r.moveToNext()) {
                            if (r.isNull(0)) continue
                            val a = maxOf(r.getLong(0), from)
                            val b = minOf(r.getLong(1), to)
                            if (a <= b) buckets += a to b
                        }
                    }
                    out += HistoryCsv.mergeSegments(buckets.sortedBy { it.first }, gapMs)
                }
            }
            out
        }

    /** [keys] の記録の一番古い時刻 (無ければ null) */
    suspend fun earliest(slot: String, keys: Collection<String>): Long? = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext null
        runCatching {
            db.rawQuery(
                "SELECT MIN(t) FROM samples WHERE g = ? AND k IN (${keys.joinToString(",") { "?" }})",
                arrayOf(slot) + keys.toTypedArray(),
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull()
    }

    /** [keys] の記録を CSV (key,datetime,value) で書き出す。書き出した行数を返す */
    suspend fun exportCsv(slot: String, keys: Collection<String>, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val fmt = HistoryCsv.dateFormat()
        var n = 0
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(CSV_HEADER); w.write("\n")
            if (keys.isNotEmpty()) {
                db.rawQuery(
                    "SELECT k, t, v FROM samples WHERE g = ? AND k IN (${keys.joinToString(",") { "?" }}) ORDER BY k, t",
                    arrayOf(slot) + keys.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        w.write(HistoryCsv.line(c.getString(0), c.getLong(1), c.getDouble(2), fmt)); w.write("\n")
                        n++
                    }
                }
            }
        }
        n
    }

    /**
     * CSV (key,datetime,value) をグラフ [slot] の記録として読み込む。同じ項目・同じ時刻の記録は上書きする。
     * @return 読み込んだ行数と、読めなかった行数
     */
    suspend fun importCsv(slot: String, input: InputStream): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val formats = HistoryCsv.importFormats()
        var ok = 0
        var bad = 0
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            val batch = ArrayList<Triple<String, Long, Double>>()
            fun flush() {
                if (batch.isEmpty()) return
                db.beginTransaction()
                try {
                    val cv = ContentValues()
                    for ((k, t, v) in batch) {
                        cv.clear()
                        cv.put("g", slot); cv.put("k", k); cv.put("t", t / 1000 * 1000); cv.put("v", v)
                        db.insertWithOnConflict("samples", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                batch.clear()
            }
            for ((i, raw) in lines.withIndex()) {
                val line = raw.removePrefix("﻿").trim()
                if (line.isEmpty()) continue
                if (i == 0 && HistoryCsv.isHeader(line)) continue // 見出し行
                val row = HistoryCsv.parseLine(line, formats)
                if (row == null) { bad++; continue }
                batch += row
                ok++
                if (batch.size >= 2000) flush()
            }
            flush()
        }
        _revision.value++
        ok to bad
    }

    /**
     * 以前の版で項目ごとに共有していた記録 (グラフ未定) を、その項目を使っているグラフそれぞれへ写して消す。
     * [slots] はグラフ → 使っている項目キー
     */
    suspend fun adoptShared(slots: Map<String, Collection<String>>) = withContext(Dispatchers.IO) {
        runCatching {
            val has = db.rawQuery("SELECT 1 FROM samples WHERE g = '' LIMIT 1", null).use { it.moveToFirst() }
            if (!has) return@runCatching
            db.beginTransaction()
            try {
                for ((g, keys) in slots) {
                    if (keys.isEmpty()) continue
                    db.execSQL(
                        "INSERT OR IGNORE INTO samples (g, k, t, v) SELECT ?, k, t, v FROM samples " +
                            "WHERE g = '' AND k IN (${keys.joinToString(",") { "?" }})",
                        (listOf<Any>(g) + keys).toTypedArray(),
                    )
                }
                db.delete("samples", "g = ''", null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            _revision.value++
        }
    }

    private fun cleanup(now: Long) {
        val slots = retentions ?: return
        for ((g, ms) in slots) {
            if (ms <= 0) continue // 変動監視: メーターを削除するまで残す
            val keep = ms.coerceIn(3_600_000L, MAX_MONTHS * MONTH_MS)
            db.delete("samples", "g = ? AND t < ?", arrayOf(g, (now - keep).toString()))
        }
        // 消したグラフ・折れ線グラフでなくなったメーターの記録 (グラフ未定の旧記録は [adoptShared] に任せる)
        if (slots.isEmpty()) db.delete("samples", "g <> ''", null)
        else db.delete("samples", "g <> '' AND g NOT IN (${slots.keys.joinToString(",") { "?" }})", slots.keys.toTypedArray())
    }
}
