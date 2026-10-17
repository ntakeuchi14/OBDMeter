package com.obdmeter.data

import com.obdmeter.core.PidDef
import com.obdmeter.core.Pids
import kotlinx.serialization.Serializable

/** 接続方式 */
enum class ConnectionType(val label: String) {
    SIMULATOR("シミュレーター (車なしで動作確認)"),
    USB("USB 接続"),
}

/** USB で使うアダプターの種類 (設定で切り替え) */
enum class UsbAdapter(val label: String, val detail: String) {
    OBDLINK("OBDLink SX / EX (ELM327 互換)", "汎用 OBD2。E90 では最も確実な方式"),
    DCAN("K+DCAN ケーブル (INPA 互換)", "BMW-FAST で DME に直接問い合わせ。実験的"),
}

data class AppSettings(
    val connectionType: ConnectionType = ConnectionType.SIMULATOR,
    val usbAdapter: UsbAdapter = UsbAdapter.OBDLINK,
    // --- OBDLink / ELM327 ---
    val elmBaudRate: Int = 115200,
    /** ATSP の値。2007/03 以降の E90 (D-CAN) に合わせて CAN 11bit 500k を既定にする */
    val elmProtocol: String = "6",
    val elmSingleResponseHint: Boolean = true,
    // --- K+DCAN ---
    val dcanTargetAddress: Int = 0x12,
    val dcanEcho: Boolean = true,
    // --- 共通 ---
    val autoReconnect: Boolean = true,
    val keepScreenOn: Boolean = true,
) {
    companion object {
        val ELM_BAUD_RATES = listOf(9600, 38400, 115200, 230400, 500000)
        val ELM_PROTOCOLS = listOf(
            "0" to "自動判別",
            "6" to "ISO 15765-4 CAN 11bit 500k (2007/03 以降の E90)",
            "5" to "ISO 14230-4 KWP 高速初期化 (2007/03 以前の E90)",
            "4" to "ISO 14230-4 KWP 5baud 初期化",
            "3" to "ISO 9141-2",
        )
    }
}

enum class GaugeStyle(val label: String) {
    ANALOG("アナログ"),
    BAR("バー"),
    DIGITAL("デジタル"),
}

/**
 * メーター 1 個ごとの見た目の上書き設定。null の項目は PID 定義の既定値を使う。
 */
@Serializable
data class GaugeDesign(
    /** 表示名 */
    val label: String? = null,
    /** 目盛りの範囲 */
    val min: Double? = null,
    val max: Double? = null,
    /** true なら警告値は PID 定義の既定値。false なら下の 2 つ (null = 警告なし) を使う */
    val useDefaultWarn: Boolean = true,
    val warnAbove: Double? = null,
    val warnBelow: Double? = null,
    /** 小数点以下の桁数 */
    val decimals: Int? = null,
    /** メーターの色 (ARGB)。null = 既定の水色 */
    val color: Long? = null,
)

/**
 * ダッシュボード上のメーター 1 個。
 * 位置と大きさは [Layout.COLS] × [Layout.ROWS] のマス目単位で持つので、画面サイズや向きが変わっても崩れない。
 */
@Serializable
data class GaugeConfig(
    val pidKey: String,
    val style: GaugeStyle = GaugeStyle.ANALOG,
    val id: Long = 0,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0,
    val design: GaugeDesign = GaugeDesign(),
)

@Serializable
data class Dashboard(
    val id: Long,
    val name: String,
    /** 旧形式 (列数で自動配置) からの移行用。新しい配置は各メーターの x/y/w/h で決まる */
    val columns: Int = 2,
    val gauges: List<GaugeConfig> = emptyList(),
)

object Layout {
    const val COLS = 24
    const val ROWS = 12

    /** 位置が未設定のメーターに id と配置を与える (旧データの移行・初期データ用) */
    fun normalize(d: Dashboard): Dashboard {
        var nextId = (d.gauges.maxOfOrNull { it.id } ?: 0L) + 1
        val withIds = d.gauges.map { if (it.id == 0L) it.copy(id = nextId++) else it }
        if (withIds.all { it.w > 0 && it.h > 0 }) return d.copy(gauges = withIds)
        return d.copy(gauges = arrangeInGrid(withIds, d.columns))
    }

    /** [columns] 列で均等に並べ直す */
    fun arrangeInGrid(gauges: List<GaugeConfig>, columns: Int): List<GaugeConfig> {
        val cols = columns.coerceIn(1, 6)
        val rows = maxOf(1, (gauges.size + cols - 1) / cols)
        return gauges.mapIndexed { i, g ->
            val c = i % cols
            val r = i / cols
            val x0 = c * COLS / cols
            val x1 = (c + 1) * COLS / cols
            val y0 = r * ROWS / rows
            val y1 = (r + 1) * ROWS / rows
            g.copy(x = x0, y = y0, w = maxOf(1, x1 - x0), h = maxOf(1, y1 - y0))
        }
    }

    /** 新しいメーターを置く空き場所を探す。見つからなければ左上に重ねる */
    fun findFreeSpot(existing: List<GaugeConfig>, w: Int, h: Int): Pair<Int, Int> {
        for (y in 0..ROWS - h) for (x in 0..COLS - w) {
            val overlaps = existing.any { g ->
                x < g.x + g.w && g.x < x + w && y < g.y + g.h && g.y < y + h
            }
            if (!overlaps) return x to y
        }
        return 0 to 0
    }

    /** 見た目の選択肢 (メーターの色) */
    val COLORS: List<Pair<String, Long>> = listOf(
        "水色" to 0xFF4FC3F7,
        "緑" to 0xFF66BB6A,
        "黄" to 0xFFFFCA28,
        "オレンジ" to 0xFFFF9800,
        "赤" to 0xFFEF5350,
        "紫" to 0xFFAB47BC,
        "ピンク" to 0xFFEC407A,
        "白" to 0xFFE8EAED,
    )
}

/**
 * ユーザーが登録するカスタム PID (OBD Fusion の「カスタム PID」相当)。
 * 応答データの 1 バイト目を A、2 バイト目を B … として [formula] で物理値に換算する。
 */
@Serializable
data class CustomPid(
    val id: Long,
    val name: String,
    val shortName: String = "",
    val unit: String = "",
    /** サービス (16 進)。01 = 標準、22 = メーカー独自 DID 読み出し など */
    val mode: String = "22",
    /** PID / DID (16 進、例 "0C" "4205") */
    val pid: String = "",
    /** ELM327: ATSH の値 (例 "7E0")。K+DCAN: 送信先 ECU アドレス (例 "12")。空 = 既定 */
    val header: String = "",
    /** ELM327 の CAN 拡張アドレス (ATCEA)。BMW 6F1 系で使う。空 = 使わない */
    val canExtAddress: String = "",
    val formula: String = "A",
    val min: Double = 0.0,
    val max: Double = 100.0,
    val decimals: Int = 0,
    val warnAbove: Double? = null,
    val warnBelow: Double? = null,
    /** 変化の遅い値 (温度など) は読む頻度を下げて、他のメーターの更新を速くする */
    val slow: Boolean = false,
) {
    val key: String get() = KEY_PREFIX + id

    /** 表示・読み出しに使う定義に変換する。式や PID が不正なら例外 (メッセージは日本語)。 */
    fun toDef(): PidDef = Pids.custom(
        key, name, shortName, unit, mode, pid, header, canExtAddress,
        formula, min, max, decimals, warnAbove, warnBelow, slow,
    )

    /** 不正な場合のエラーメッセージ。正しければ null */
    fun validate(): String? = runCatching { toDef() }.exceptionOrNull()?.message

    companion object {
        const val KEY_PREFIX = "CUSTOM_"
    }
}

object Defaults {
    val dashboards: List<Dashboard> = listOf(
        Dashboard(
            id = 1, name = "メイン", columns = 2,
            // 回転数を大きく左に、右側に車速・水温・バッテリー
            gauges = listOf(
                GaugeConfig("RPM", GaugeStyle.ANALOG, id = 1, x = 0, y = 0, w = 12, h = 12),
                GaugeConfig("SPEED", GaugeStyle.ANALOG, id = 2, x = 12, y = 0, w = 12, h = 8),
                GaugeConfig("COOLANT", GaugeStyle.BAR, id = 3, x = 12, y = 8, w = 6, h = 4),
                GaugeConfig("BATTERY", GaugeStyle.DIGITAL, id = 4, x = 18, y = 8, w = 6, h = 4),
            ),
        ),
        Dashboard(
            id = 2, name = "エンジン", columns = 3,
            gauges = listOf(
                GaugeConfig("LOAD", GaugeStyle.ANALOG),
                GaugeConfig("THROTTLE", GaugeStyle.ANALOG),
                GaugeConfig("TIMING", GaugeStyle.ANALOG),
                GaugeConfig("MAF", GaugeStyle.DIGITAL),
                GaugeConfig("MAP", GaugeStyle.DIGITAL),
                GaugeConfig("IAT", GaugeStyle.DIGITAL),
            ),
        ),
        Dashboard(
            id = 3, name = "燃調・燃費", columns = 2,
            gauges = listOf(
                GaugeConfig("STFT1", GaugeStyle.BAR),
                GaugeConfig("STFT2", GaugeStyle.BAR),
                GaugeConfig("LTFT1", GaugeStyle.BAR),
                GaugeConfig("LTFT2", GaugeStyle.BAR),
                GaugeConfig("FUEL_RATE", GaugeStyle.DIGITAL),
                GaugeConfig("ECONOMY", GaugeStyle.DIGITAL),
            ),
        ),
    ).map(Layout::normalize)
}
