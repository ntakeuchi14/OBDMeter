package com.obdmeter

import com.obdmeter.core.CommLogger
import com.obdmeter.core.ObdProtocol
import com.obdmeter.data.AppSettings
import com.obdmeter.data.FontEntry
import com.obdmeter.data.HistoryStore
import com.obdmeter.data.Repository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/** 画面の夜用の状態。[nightMode] = Android のナイトモード、[brightness] = 画面の明るさの設定値 (0〜255。読めなければ null) */
data class DisplayLight(
    val nightMode: Boolean = false,
    val brightness: Int? = null,
    /** 見ている端末の値 ([AppPlatform.watchSystemValue]) の今の値 */
    val custom: String? = null,
)

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

    /** 画面の夜用の状態 (ライト連動用。読めない環境は null) */
    val displayLight: StateFlow<DisplayLight>? get() = null

    /** 端末の設定値・システムの値をすべて読む (ライト連動の調査用。名前は "system:…" など)。読めない環境は null */
    suspend fun systemValues(): Map<String, String>? = null

    /** [displayLight] の custom で見る端末の値 (空 = 見ない) */
    fun watchSystemValue(key: String) {}

    /** 接続テスト (アダプターと車両の応答を順に確かめる)。できない環境なら例外 */
    suspend fun connectionTest(settings: AppSettings, log: CommLogger, out: (String) -> Unit) {
        throw UnsupportedOperationException("この環境では接続テストを使えません")
    }

    /** BMW の値の探索。できない環境なら例外 */
    suspend fun bmwSearch(settings: AppSettings, log: CommLogger, targets: List<Long>, ranges: List<Pair<Int, IntRange>>, out: (String) -> Unit) {
        throw UnsupportedOperationException("この環境では値の探索を使えません")
    }
}
