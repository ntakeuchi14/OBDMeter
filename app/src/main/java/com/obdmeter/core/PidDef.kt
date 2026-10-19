package com.obdmeter.core

/**
 * メーターに表示できる項目の定義。
 * - MODE01: OBD2 Mode 01 の PID を読んで [decode] で物理値に変換
 * - ADAPTER_VOLTAGE: ELM327 の ATRV (アダプター側で測るバッテリー電圧)
 * - DERIVED: 他の項目から計算 (ブースト圧、燃料消費量など)
 */
data class PidDef(
    val key: String,
    val name: String,
    val shortName: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int = 0,
    val warnAbove: Double? = null,
    val warnBelow: Double? = null,
    val kind: Kind = Kind.MODE01,
    val pid: Int = -1,
    val minBytes: Int = 1,
    /** 表示の丸め単位 (0 なら丸めない)。回転数は 10rpm 単位にして数字の震えを抑える。 */
    val displayStep: Double = 0.0,
    /** 変化が遅い項目は数サイクルに 1 回だけ読む (更新速度を稼ぐため)。 */
    val slow: Boolean = false,
    val decode: (IntArray) -> Double = { 0.0 },
    val deps: List<String> = emptyList(),
    val derive: (Map<String, Double>) -> Double? = { null },
    /** CUSTOM のときの問い合わせ内容 */
    val request: PidRequest? = null,
    /** BMW_BLOCK: DME の測定値 ID とバイト数 ([decode] にはこの項目のバイトだけを渡す) */
    val blockId: Int = 0,
    val blockSize: Int = 0,
) {
    /**
     * MODE01: 標準 PID / ADAPTER_VOLTAGE: アダプター測定 / DERIVED: 計算値 /
     * CUSTOM: ユーザー登録のカスタム PID / REQUEST: Mode 01 以外の標準要求 (Mode 06 の失火カウンタなど)
     */
    enum class Kind { MODE01, ADAPTER_VOLTAGE, DERIVED, CUSTOM, REQUEST, BMW_BLOCK }

    val isCustom: Boolean get() = kind == Kind.CUSTOM

    fun format(value: Double?): String =
        if (value == null) "---" else "%.${decimals}f".format(value)
}
