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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 記録した値 1 つ (時刻はミリ秒) */
class Sample(val t: Long, val v: Float)

/**
 * 折れ線グラフ用の値の記録。項目ごとに「時刻 (秒単位)・値」を端末のデータベースに保存する。
 * - 記録するのは [intervals] の項目 (どこかの折れ線グラフで使われている項目)。間隔の管理は呼び出し側
 * - 保存期間 ([retentionMs]) を過ぎた記録は自動で消す
 * - CSV (項目キー, 年月日時分秒, 値) で書き出し・読み込みができる
 */
class HistoryStore(context: Context, private val scope: CoroutineScope) {

    companion object {
        const val MIN_INTERVAL_SEC = 1
        const val MAX_INTERVAL_SEC = 600
        const val MAX_MONTHS = 24
        const val MONTH_MS = 2_629_746_000L // 1 か月 (平均 30.44 日)
        const val CSV_HEADER = "key,datetime,value"

        /** CSV の時刻の書式 (端末のタイムゾーン) */
        fun dateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        /** CSV 1 行を (キー, 時刻ミリ秒, 値) にする。読めなければ null */
        fun parseCsvLine(line: String, formats: List<SimpleDateFormat>): Triple<String, Long, Double>? {
            val cols = line.split(',').map { it.trim().trim('"') }
            if (cols.size < 3) return null
            val key = cols[0].takeIf { it.isNotEmpty() } ?: return null
            val t = formats.firstNotNullOfOrNull { f -> runCatching { f.parse(cols[1])?.time }.getOrNull() } ?: return null
            val v = cols[2].toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() } ?: return null
            return Triple(key, t, v)
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "history.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            // 同じ項目・同じ時刻の記録は 1 つだけ (読み込みで重ねても増えない)
            db.execSQL("CREATE TABLE samples (k TEXT NOT NULL, t INTEGER NOT NULL, v REAL NOT NULL, PRIMARY KEY (k, t)) WITHOUT ROWID")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 旧形式 (1 秒ごとの細かい記録) は引き継がない
            db.execSQL("DROP TABLE IF EXISTS samples")
            onCreate(db)
        }
    }

    private val helper = Helper(context.applicationContext)
    private val db: SQLiteDatabase by lazy { helper.writableDatabase }

    /** 記録する項目のキーと記録間隔 (ミリ秒) */
    @Volatile var intervals: Map<String, Long> = emptyMap()
    /** 保存期間 (ミリ秒) */
    @Volatile var retentionMs: Long = 3 * MONTH_MS

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

    /** 値を記録する。時刻は秒単位に丸める */
    suspend fun store(values: Map<String, Double>, time: Long) = withContext(Dispatchers.IO) {
        if (values.isEmpty()) return@withContext
        val t = time / 1000 * 1000
        runCatching {
            db.beginTransaction()
            try {
                val cv = ContentValues()
                for ((k, v) in values) {
                    if (v.isNaN() || v.isInfinite()) continue
                    cv.clear()
                    cv.put("k", k); cv.put("t", t); cv.put("v", v)
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
    suspend fun load(key: String, from: Long, to: Long, maxPoints: Int): List<Sample> = withContext(Dispatchers.IO) {
        val out = ArrayList<Sample>()
        val bucket = ((to - from) / (maxPoints / 2).coerceAtLeast(1)).coerceAtLeast(1000L)
        runCatching {
            db.rawQuery(
                "SELECT (t - ?) / ? AS b, MIN(v), MAX(v), MIN(t), MAX(t), COUNT(*) FROM samples " +
                    "WHERE k = ? AND t >= ? AND t <= ? GROUP BY b ORDER BY b",
                arrayOf(from.toString(), bucket.toString(), key, from.toString(), to.toString()),
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
    suspend fun neighbors(key: String, from: Long, to: Long): Pair<Sample?, Sample?> = withContext(Dispatchers.IO) {
        fun one(sql: String, arg: Long): Sample? = runCatching {
            db.rawQuery(sql, arrayOf(key, arg.toString())).use { c -> if (c.moveToFirst()) Sample(c.getLong(0), c.getFloat(1)) else null }
        }.getOrNull()
        one("SELECT t, v FROM samples WHERE k = ? AND t < ? ORDER BY t DESC LIMIT 1", from) to
            one("SELECT t, v FROM samples WHERE k = ? AND t > ? ORDER BY t ASC LIMIT 1", to)
    }

    /** [keys] の記録の一番古い時刻 (無ければ null) */
    suspend fun earliest(keys: Collection<String>): Long? = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext null
        runCatching {
            db.rawQuery(
                "SELECT MIN(t) FROM samples WHERE k IN (${keys.joinToString(",") { "?" }})",
                keys.toTypedArray(),
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull()
    }

    /** [keys] の記録を CSV (key,datetime,value) で書き出す。書き出した行数を返す */
    suspend fun exportCsv(keys: Collection<String>, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val fmt = dateFormat()
        var n = 0
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(CSV_HEADER); w.write("\n")
            if (keys.isNotEmpty()) {
                db.rawQuery(
                    "SELECT k, t, v FROM samples WHERE k IN (${keys.joinToString(",") { "?" }}) ORDER BY k, t",
                    keys.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        w.write(c.getString(0)); w.write(",")
                        w.write(fmt.format(Date(c.getLong(1)))); w.write(",")
                        w.write(formatValue(c.getDouble(2))); w.write("\n")
                        n++
                    }
                }
            }
        }
        n
    }

    /**
     * CSV (key,datetime,value) を読み込む。同じ項目・同じ時刻の記録は上書きする。
     * @return 読み込んだ行数と、読めなかった行数
     */
    suspend fun importCsv(input: InputStream): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val formats = listOf(
            dateFormat(),
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
        ).onEach { it.isLenient = false }
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
                        cv.put("k", k); cv.put("t", t / 1000 * 1000); cv.put("v", v)
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
                if (i == 0 && line.lowercase().startsWith("key")) continue // 見出し行
                val row = parseCsvLine(line, formats)
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

    private fun formatValue(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun cleanup(now: Long) {
        val keep = retentionMs.coerceIn(3_600_000L, MAX_MONTHS * MONTH_MS)
        db.delete("samples", "t < ?", arrayOf((now - keep).toString()))
    }
}
