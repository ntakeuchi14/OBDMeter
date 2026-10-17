package com.obdmeter.data

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

@Serializable
data class GaugeConfig(
    val pidKey: String,
    val style: GaugeStyle = GaugeStyle.ANALOG,
)

@Serializable
data class Dashboard(
    val id: Long,
    val name: String,
    val columns: Int = 2,
    val gauges: List<GaugeConfig> = emptyList(),
)

object Defaults {
    val dashboards = listOf(
        Dashboard(
            id = 1, name = "メイン", columns = 2,
            gauges = listOf(
                GaugeConfig("RPM", GaugeStyle.ANALOG),
                GaugeConfig("SPEED", GaugeStyle.ANALOG),
                GaugeConfig("COOLANT", GaugeStyle.BAR),
                GaugeConfig("BATTERY", GaugeStyle.DIGITAL),
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
    )
}
