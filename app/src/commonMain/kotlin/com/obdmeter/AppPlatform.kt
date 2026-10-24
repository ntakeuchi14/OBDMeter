package com.obdmeter

import com.obdmeter.core.CommLogger
import com.obdmeter.core.ObdProtocol
import com.obdmeter.data.AppSettings
import com.obdmeter.data.FontEntry
import com.obdmeter.data.HistoryStore
import com.obdmeter.data.Repository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/** 端末のセンサーの値 (傾き・加速度・テスト信号など) */
interface DeviceSensorSource {
    val values: StateFlow<Map<String, Double>>
    /** 使っている項目を伝える (使わないセンサーは止める) */
    fun setActive(keys: Set<String>)
}

/**
 * Android と Web で違うところ (保存先・記録・アダプター・ファイル)。共通のコード ([AppCore]・画面) はここを通して使う
 */
interface AppPlatform {
    val repo: Repository
    val history: HistoryStore
    val deviceSensors: DeviceSensorSource

    /** 通信を行うスレッド (Android は IO、Web は既定) */
    val ioDispatcher: CoroutineDispatcher

    /** 読み出しのサイクルの間に必ず休む時間 (Web は画面を止めないよう少し休む) */
    val minCycleMs: Long

    /** 接続方式に応じたプロトコルを作って開く。[status] で途中の状態を伝える */
    suspend fun createProtocol(settings: AppSettings, log: CommLogger, status: (String) -> Unit): ObdProtocol

    /** ダッシュボードの 1 マスの縦横比の初期値 (画面の大きさから) */
    fun defaultCellAspect(): Float

    /** アプリのバージョン (バックアップに書く) */
    val appVersion: String

    /** どのメーターからも使われていない画像を消す */
    suspend fun cleanupImages(used: Set<String>)

    /** 選んだ画像を取り込み (長辺 [maxSize] まで縮小)、名前を返す。読めない画像なら例外 */
    suspend fun importImage(bytes: ByteArray, maxSize: Int): String

    /** 画像のデータを保存し、名前を返す。画像として読めなければ null */
    suspend fun saveImageBytes(bytes: ByteArray): String?

    /** フォントを保存する。同じ内容のフォントが [existing] にあればそれを返す。読めないフォントなら例外 */
    suspend fun saveFont(bytes: ByteArray, displayName: String, existing: List<FontEntry>): FontEntry

    /** 保存したフォントのデータ (バックアップ用)。無ければ null */
    suspend fun fontBytes(entry: FontEntry): ByteArray?

    /** 保存した画像のデータ (バックアップ用)。無ければ null */
    suspend fun imageBytes(name: String): ByteArray?

    fun deleteFont(entry: FontEntry)

    /** 接続テスト (アダプターと車両の応答を順に確かめる)。できない環境なら例外 */
    suspend fun connectionTest(settings: AppSettings, log: CommLogger, out: (String) -> Unit) {
        throw UnsupportedOperationException("この環境では接続テストを使えません")
    }

    /** BMW の値の探索。できない環境なら例外 */
    suspend fun bmwSearch(settings: AppSettings, log: CommLogger, targets: List<Long>, ranges: List<Pair<Int, IntRange>>, out: (String) -> Unit) {
        throw UnsupportedOperationException("この環境では値の探索を使えません")
    }
}
