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
    /** アプリ起動時に自動で接続する */
    val autoConnect: Boolean = false,
    /** ダッシュボード上部のバーを常に表示する (OFF なら右上のボタンで呼び出す) */
    val alwaysShowToolbar: Boolean = false,
    /** イグニッション OFF でもアダプターに接続し、車両が応答するまで待つ */
    val waitForVehicle: Boolean = true,
    /** 待機の上限 (分)。0 = 無制限。待機中も車両に問い合わせるので、バッテリー保護のため */
    val waitTimeoutMin: Int = 15,
    val keepScreenOn: Boolean = true,
    // --- フロート表示 (ほかのアプリやホーム画面の上にメーターを出す) ---
    /** フロート表示するページの id (null = 先頭のページ) */
    val floatPageId: Long? = null,
    /** メーターごとのフロート表示の位置・大きさ (キーはメーターの id)。無いメーターは自動で並べる */
    val floatPlacements: Map<Long, FloatPlacement> = emptyMap(),
    /** フロート表示全体の不透明度 (0.2〜1) */
    val floatOpacity: Float = 1f,
    /** フロート表示の背景の濃さ (0 = 透明) */
    val floatBackground: Float = 0f,
    /**
     * フロート表示: ホームボタンなどでアプリを離れたら、表示していたページのメーターを
     * ほかのアプリやホーム画面の上に小さなウィンドウで出す。ON のときは起動時の自動接続も ON 扱い
     */
    val floatOnLeave: Boolean = false,
    /** アプリを起動したら、すぐにホーム画面に戻ってフロート表示で始める (フロート表示が ON のときだけ) */
    val startInFloat: Boolean = false,
    /** iDrive (ダイヤル・方向キー) でダッシュボードのページを切り替える */
    val keyPageNav: Boolean = true,
    /** レイアウト編集で、移動・大きさ変更を吸着させる間隔 ([Layout.GRID_STEPS]、単位) */
    val gridStep: Int = Layout.SCALE,
    /** 項目ごとの取得周期 (項目キー → ミリ秒)。無い項目は毎回 (遅い項目は 8 回に 1 回) */
    val pollIntervalsMs: Map<String, Int> = emptyMap(),
    /** 最後に表示していたダッシュボードのページ (次の起動時にこのページから始める) */
    val lastPageId: Long? = null,
) {
    /** 起動時に自動接続するか (フロート表示が ON なら常に接続する) */
    val autoConnectEffective: Boolean get() = autoConnect || floatOnLeave

    companion object {
        val ELM_BAUD_RATES = listOf(9600, 38400, 115200, 230400, 500000, 1000000, 2000000)
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
    /** 2 値ランプ: 条件式を満たすと点灯 */
    LAMP("ランプ"),
    /** 折れ線グラフ: 複数項目の推移 */
    CHART("折れ線グラフ"),
    /** 値テーブル: 項目名・値・単位を縦に並べる (最大行数を超えたら段組み) */
    TABLE("値テーブル"),
    /** 変動監視: 複数項目の値が変わった回数を期間ごとにランキング表示 (記録はメーターを削除するまで残す) */
    MONITOR("変動監視"),
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

    // ---- 角度 (アナログ) : 12 時 = 0°、時計回りが正 ----
    val startAngle: Float = -120f,
    val endAngle: Float = 120f,
    /** アナログ: 開始角度から終了角度へ反時計回りに進む (下側の扇形を左から右へ動かすときなど) */
    val counterClockwise: Boolean = false,

    // ---- 目盛り ----
    /** 主目盛りの区間数。null = 範囲から自動 */
    val majorDivisions: Int? = null,
    /** 主目盛りの間の小目盛りの数 */
    val minorDivisions: Int = 0,
    val showLabels: Boolean = true,
    /** 目盛りを描く位置 (外周 = 1.0)。背景画像の枠の内側に合わせる */
    val tickRadius: Float = 1f,
    val tickColor: Long? = null,
    val labelColor: Long? = null,
    /** 目盛り数字の大きさ (1.0 = 標準) */
    val labelSize: Float = 1f,

    // ---- リング・弧 ----
    val ring: RingStyle = RingStyle.NONE,
    val ringColor: Long? = null,
    /** リングの太さ (1.0 = 標準) */
    val ringThickness: Float = 1f,
    /** リングの立体感 (0 = 平ら、1 = 強い陰影)。メタル・太線で使う */
    val ringDepth: Float = 0.6f,
    /** メタルの内側の段 (凹んだ部分) の幅。リング全体に対する比率 (0 = 段なし) */
    val ringInner: Float = 0.4f,
    /** 目盛りに沿った下地の弧 */
    val showTrack: Boolean = true,
    /** 現在値まで伸びる色付きの弧 */
    val showValueArc: Boolean = true,
    /** 弧の太さ (1.0 = 標準) */
    val arcWidth: Float = 1f,

    // ---- レッドゾーン ----
    val showRedZone: Boolean = true,
    /** null なら警告値 (上限〜最大、または最小〜下限) を使う */
    val redZoneFrom: Double? = null,
    val redZoneTo: Double? = null,
    val redZoneColor: Long? = null,

    // ---- 針 ----
    val showNeedle: Boolean = true,
    val needleColor: Long? = null,
    /** 針の長さ (1.0 = 目盛りまで) */
    val needleLength: Float = 1f,
    /** 針の太さ (1.0 = 標準) */
    val needleWidth: Float = 1f,
    /** 中心から反対側に出る尾の長さ (針の長さに対する比率) */
    val needleTail: Float = 0.12f,
    /** 中心のノブの色。null = 針と同じ色 */
    val knobColor: Long? = null,
    /** ノブの大きさ (1.0 = 標準、0 = なし) */
    val knobSize: Float = 1f,

    // ---- ピーク値 ----
    /** これまでの最大値の位置に印を付ける (メーターを長押しでリセット) */
    val showPeak: Boolean = false,
    val peakColor: Long? = null,
    /** 最大値を数字でも表示する */
    val showPeakValue: Boolean = false,
    /** 最大値の文字の色 (null = 印の色と同じ) */
    val peakTextColor: Long? = null,
    /** 最大値の文字の大きさ (1 = 標準) */
    val peakTextSize: Float = 1f,
    /** 最大値の文字の位置のずれ (メーターの幅・高さに対する割合。右・下が +) */
    val peakTextX: Float = 0f,
    val peakTextY: Float = 0f,

    // ---- 数値・名前 ----
    val showValue: Boolean = true,
    val valueColor: Long? = null,
    /** 数値の縦位置 (中心からの距離、半径に対する比率。下が正) */
    val valuePosition: Float = 0.42f,
    /** 数値の横位置 (中心からの距離、半径に対する比率。右が正) */
    val valueOffsetX: Float = 0f,
    val valueSize: Float = 1f,
    val showName: Boolean = true,
    val showUnit: Boolean = true,

    // ---- フォント (取り込んだフォントの id。null = 既定) ----
    /** 数値 (現在値・最大値) のフォント。既定は等幅 */
    val valueFont: String? = null,
    /** 文字 (目盛りの数字・名前・単位) のフォント */
    val labelFont: String? = null,

    // ---- 背景 ----
    /** アプリ内に保存した背景画像のファイル名。null = 画像なし */
    val backgroundImage: String? = null,
    /** 背景画像の大きさ (1.0 = メーター枠いっぱい) */
    val imageScale: Float = 1f,
    /** 名前・単位の位置のずれ (メーターの大きさに対する比率。右・下が正) と色 (null = 文字の色と同じ) */
    val nameOffsetX: Float = 0f,
    val nameOffsetY: Float = 0f,
    val nameColor: Long? = null,
    val unitOffsetX: Float = 0f,
    val unitOffsetY: Float = 0f,
    val unitColor: Long? = null,
    /** メーターの背景色 (文字盤の色)。背景画像を使わないときだけ塗る。null = 塗らない */
    val faceColor: Long? = null,
    /** 背景色を塗る大きさ (倍率)。透過した背景画像の内側に合わせる */
    val faceScale: Float = 1f,
    /** 背景色 (メーターの背景・値テーブルの値の部分) の立体感: なし / 埋め込み / 浮き出し */
    val panelStyle: PanelStyle = PanelStyle.FLAT,
    /** 立体感の強さ (0〜1) */
    val panelDepth: Float = 0.6f,
    /** ガラスのような映り込み (上半分をうっすら明るく) */
    val panelGloss: Boolean = false,
    /** アナログの針 (とノブ) に影を付ける */
    val needleShadow: Boolean = false,
    /** メーター自体に影を付ける (カード・文字盤・背景色の形に合わせる) */
    val objectShadow: Boolean = false,
    /** 影の濃さ (0〜1.5) */
    val shadowStrength: Float = 1f,
    /** バー表示: バーの角度 (度、時計回り。0 = 横) */
    val barAngle: Float = 0f,
    /** バー表示: 破線のマスの数 (0 = 実線) と、マスの間のすき間 (マスの幅に対する比率) */
    val barSegments: Int = 0,
    val barSegmentGap: Float = 0.25f,
    /** バー表示: 弧の形にする (開始・終了角度はアナログと共通) */
    val barArc: Boolean = false,
    /** バー表示: 先細り (0 = 一定の太さ、1 = 始まりが細く終わりに向かって太くなる) */
    val barTaper: Float = 0f,
    /** バー表示: 消えているマス・背景の色 (null = 自動) */
    val barOffColor: Long? = null,
    /** バー表示: マスの色を範囲で塗り分ける (通常 → 中間 → レッドゾーン) */
    val barZoneColors: Boolean = false,
    val barMidColor: Long? = null,
    /** 中間の色にする値 (null = 中間の色なし) */
    val barMidFrom: Double? = null,
    /** アナログ: 目盛りの線を表示する */
    val showTicks: Boolean = true,
    /** アナログ: 弧の端を丸くする */
    val arcRoundCap: Boolean = false,
    /** アナログ: 背景の弧の色 (null = 既定の灰色) */
    val trackColor: Long? = null,
    /** アナログ: 現在値の弧の先に丸い点を付ける */
    val arcTipDot: Boolean = false,
    val arcTipColor: Long? = null,
    /** 値・針の動きのなめらかさ (0 = 即座に追従、1 = とてもなめらか)。0.5 が従来の動き */
    val smoothing: Float = 0.5f,
    /** 発光 (にじみ) の強さ (0〜1) */
    val glow: Float = 0f,
    /** 数値の後ろに消えている 7 セグ (8) をうっすら出す */
    val ghostDigits: Boolean = false,
    val ghostAlpha: Float = 0.15f,
    /** 背景画像の位置のずれ (メーターの大きさに対する比率。右・下が正) */
    val imageOffsetX: Float = 0f,
    val imageOffsetY: Float = 0f,
    /** 四角いカードの背景と枠を表示する。丸い画像を使うときは OFF がきれい */
    val showCard: Boolean = true,
    // --- 2 値ランプ ---
    /** 点灯条件 (値を x と書く。例: x >= 100)。空欄 = 警告値を超えたら点灯 */
    val lampCondition: String = "",
    /** 点灯時・消灯時の色 (null = 既定: 赤 / 暗い灰色) */
    val lampOnColor: Long? = null,
    val lampOffColor: Long? = null,
    // --- 値テーブル ---
    /** 1 段に並べる最大の行数 (超えたら右に段を増やす) */
    val tableMaxRows: Int = 5,
    /** 値の部分の背景色・背景画像 (null = なし) */
    val tableValueBg: Long? = null,
    val tableValueImage: String? = null,
    /** 1 行の中の項目名・単位の幅 (行の幅に対する比率。残りが値) */
    val tableNameRatio: Float = 0.4f,
    val tableUnitRatio: Float = 0.2f,
    /** 値テーブルで、単位を別の欄にせず値のすぐ後ろに付ける */
    val tableUnitInline: Boolean = false,
    /** 値テーブルで、項目名を別の欄にせず値の枠の左上に表示する */
    val tableNameInline: Boolean = false,
    /** 名前・単位の文字の大きさ (倍率)。すべての表示形式で使う (名前は値テーブルの項目名にも使う) */
    val tableNameSize: Float = 1f,
    val tableUnitSize: Float = 1f,
    /** 値テーブルの各行の下・各段の右の余白 (行の高さ・段の幅に対する比率) */
    val tableMarginBottom: Float = 0f,
    val tableMarginRight: Float = 0f,
    /** 値テーブル: 上下に並んだ値と値の間 (dp。最後の行の下には付けない) */
    val tableRowGap: Float = 0f,
    /** 値テーブル: 左右に並んだ段と段の間 (dp。最後の段の右には付けない) */
    val tableColGap: Float = 0f,
    // --- 折れ線グラフ ---
    /** 1 画面に表示する期間 (秒)。左右のスクロールで記録の全期間を見られる */
    val chartSpanSec: Int = 3600,
    /** (旧設定・未使用) */
    val historyDays: Int = 1,
    /** 記録を残す期間 (か月) */
    val historyMonths: Int = 3,
    /** 記録を残す期間 (時間)。0 より大きければ [historyMonths] の代わりにこちらを使う (1 時間・1 日・1 週間) */
    val historyHours: Int = 0,
    /** 記録する間隔 (秒、1〜600)。接続した直後に 1 回記録し、その後この間隔で記録する */
    val recordIntervalSec: Float = 60f,
    /**
     * 折れ線グラフ: 記録する間隔を使わず、項目の取得周期で値を見て、前回記録した値から変わったときだけ記録する
     * (変わらない間は、最後に見た時刻の行を 1 つだけ持って更新する。[com.obdmeter.data.ChangeRecorder])
     */
    val recordOnChange: Boolean = false,
    /** 折れ線グラフで、どの項目にも記録が無い時間を詰めて表示する */
    val chartSkipGaps: Boolean = true,
    /** 変動監視: 最初に表示する期間 (ミリ秒。[com.obdmeter.data.Monitor.WINDOWS] のどれか) */
    val monitorWindowMs: Long = 86_400_000L,
    /** 折れ線グラフの凡例の位置 */
    val legendPosition: LegendPosition = LegendPosition.TOP,
    /** 折れ線グラフの凡例の寄せ方 (上・下に置くとき) */
    val legendAlign: LegendAlign = LegendAlign.START,
    /** 折れ線グラフの凡例の大きさ (文字と色の丸。1 = 標準) */
    val legendScale: Float = 1f,
    /** 折れ線グラフの凡例をグラフ内に置くときの背景の濃さ (0 = 透明) */
    val legendBgAlpha: Float = 0.5f,
    /** 折れ線グラフ: そのページを表示している間だけ記録する (false = ほかのページを表示中も記録する) */
    val recordOnlyWhenShown: Boolean = false,
    /** 項目ごとの線の色 (並びは「表示する項目」→ 追加した項目の順。null = 自動) */
    val chartColors: List<Long?> = emptyList(),
)

/** 折れ線グラフの凡例 (項目名と現在値) の位置 */
enum class LegendPosition(val label: String) {
    TOP("上"),
    BOTTOM("下"),
    INSIDE_TOP_LEFT("グラフ内 左上"),
    INSIDE_TOP_RIGHT("グラフ内 右上"),
    INSIDE_BOTTOM_LEFT("グラフ内 左下"),
    INSIDE_BOTTOM_RIGHT("グラフ内 右下"),
    HIDDEN("出さない"),
    ;
    val inside: Boolean get() = name.startsWith("INSIDE")
}

/** 凡例を上・下に置くときの横の寄せ方 */
enum class LegendAlign(val label: String) { START("左寄せ"), CENTER("中央"), END("右寄せ") }

/** 背景色の立体感 */
enum class PanelStyle(val label: String) {
    FLAT("なし (平ら)"),
    INSET("埋め込み (へこみ)"),
    RAISED("浮き出し"),
}

/** メーター外周のリング */
enum class RingStyle(val label: String) {
    NONE("なし"),
    LINE("細線"),
    BOLD("太線"),
    METAL("メタル"),
}

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
    /**
     * 代替項目のキー。メイン項目が読めないとき (イグニッション OFF で標準 PID が拒否されるときなど)
     * に、代わりにこの項目の値を表示する。カスタム PID を指定することを想定。
     */
    val fallbackKey: String? = null,
    /**
     * フロート表示のグループ (1〜[FLOAT_GROUPS])。同じページで同じグループのメーターは、
     * フロート表示で 1 つのウィンドウにまとめて、ダッシュボードと同じ並びで表示する。null = 単独
     */
    val floatGroup: Int? = null,
    /**
     * 配置のグループ (同じ値のメーターをまとめて移動・大きさ変更・コピーする)。null = グループなし。
     * 2 つ以上のメーターが同じ値のときだけグループとして扱う
     */
    val layoutGroup: Long? = null,
    /** フロート表示に出すか (false = ダッシュボードだけに表示) */
    val floatShow: Boolean = true,
    /** 折れ線グラフで、[pidKey] に加えて表示する項目のキー */
    val chartKeys: List<String> = emptyList(),
    /** 値テーブル・折れ線グラフの追加項目の表示名 (項目キー → 名前)。先頭の項目は design.label を使う */
    val itemLabels: Map<String, String> = emptyMap(),
    /** 折れ線グラフの各項目が右の縦軸に属するか (項目キー → true = 右)。無ければ奇数番目が右、偶数番目が左 */
    val chartAxis: Map<String, Boolean> = emptyMap(),
) {
    /** 折れ線グラフの [index] 番目 (0 始まり) の項目が右の縦軸か */
    fun isRightAxis(index: Int, key: String): Boolean = chartAxis[key] ?: (index % 2 == 0)

    /** 折れ線グラフに出す項目すべて (先頭が [pidKey]) */
    val chartAllKeys: List<String> get() = listOf(pidKey) + chartKeys

    companion object {
        /** 折れ線グラフに追加できる項目の数 */
        const val MAX_CHART_EXTRA = 5
        /** 値テーブルに追加できる項目の数 */
        const val MAX_TABLE_EXTRA = 29
        const val FLOAT_GROUPS = 6
        fun groupLabel(group: Int): String = ('A' + (group - 1)).toString()
    }
}

/** フロート表示したメーター 1 つの位置 (画面左上からの px) と幅 (dp)。高さはメーターの形で決まる */
@Serializable
data class FloatPlacement(val x: Int, val y: Int, val widthDp: Int)

/** メーターが使っている画像ファイル名 (背景画像・値テーブルの値の背景) */
/** 記録を残す期間 (ミリ秒) */
val GaugeDesign.historyRetentionMs: Long
    get() = if (historyHours > 0) historyHours * 3_600_000L else historyMonths.coerceIn(1, 24) * 2_629_746_000L

fun GaugeDesign.imageNames(): List<String> = listOfNotNull(backgroundImage, tableValueImage)

/** ページとメーターが使っている画像の名前 */
fun Dashboard.imageNames(): List<String> = gauges.flatMap { it.design.imageNames() } + listOfNotNull(background.image)

/** ページの背景の柄 */
enum class BgPattern(val label: String) {
    NONE("なし"),
    GRID("格子"),
    CHECK("チェック"),
    STRIPE("ストライプ"),
    DOT("ドット"),
    WOOD("木目"),
    CARBON("カーボン"),
    HONEYCOMB("ハニカム"),
}

/**
 * ページ (ダッシュボード) の背景。下から 色 → 柄 → 画像 の順に重ね、メーターは必ずその上に描く
 */
@Serializable
data class PageBackground(
    /** 背景色 (null = アプリの既定の色) */
    val color: Long? = null,
    val pattern: BgPattern = BgPattern.NONE,
    /** 柄の色 (null = 背景色に合わせて自動) */
    val patternColor: Long? = null,
    /** 柄の大きさ (1 = 標準) */
    val patternScale: Float = 1f,
    /** 柄の濃さ (0〜1) */
    val patternAlpha: Float = 0.35f,
    /** 背景画像 (null = なし) */
    val image: String? = null,
    /** 画像の中心の位置 (ページの幅・高さに対する割合。0.5 = 中央) */
    val imageX: Float = 0.5f,
    val imageY: Float = 0.5f,
    /** 画像の幅・高さ (ページの幅・高さに対する割合) */
    val imageWidth: Float = 1f,
    val imageHeight: Float = 1f,
    /** 画像の縦横比を保つ (true なら高さは幅から決める) */
    val keepAspect: Boolean = true,
    /** 画像の不透明度 */
    val imageAlpha: Float = 1f,
)

@Serializable
data class Dashboard(
    val id: Long,
    val name: String,
    /** 旧形式 (列数で自動配置) からの移行用。新しい配置は各メーターの x/y/w/h で決まる */
    val columns: Int = 2,
    val gauges: List<GaugeConfig> = emptyList(),
    /** ページの背景 (色・柄・画像) */
    val background: PageBackground = PageBackground(),
    /**
     * メーターの位置・大きさの単位 (旧来の 1 マスを何分割した単位か)。
     * 以前の版 (1 = 24 × 12 マス) の保存データは、読み込み時に [Layout.SCALE] 倍して細かい単位に直す
     */
    val unitScale: Int = 1,
)

object Layout {
    /** 旧来の 1 マス (横 24 × 縦 12 分割) を、さらに何分割して位置・大きさを持つか */
    const val SCALE = 4
    /** ページの横・縦の単位数 (96 × 48) */
    const val COLS = 24 * SCALE
    const val ROWS = 12 * SCALE
    /** メーターの最小の大きさ (単位) */
    const val MIN_SIZE = 2 * SCALE
    /** 移動・大きさ変更の吸着の間隔の選択肢 (単位)。4 = 旧来の 1 マス */
    val GRID_STEPS = listOf(1 to "1/4 マス", 2 to "1/2 マス", 4 to "1 マス", 8 to "2 マス")

    /** [v] を [step] の倍数に丸める */
    fun snap(v: Int, step: Int): Int = if (step <= 1) v else Math.round(v.toDouble() / step).toInt() * step

    /** 幅の範囲 (ページの 2 倍まで) */
    val widthRange: IntRange get() = MIN_SIZE..COLS * 2
    val heightRange: IntRange get() = MIN_SIZE..ROWS * 2
    /** 幅 [w] のメーターを置ける横の位置 (ページの外にはみ出してよいが、つかめるよう 1 マスはページ内に残す) */
    fun xRange(w: Int): IntRange = (SCALE - w)..(COLS - SCALE)
    fun yRange(h: Int): IntRange = (SCALE - h)..(ROWS - SCALE)

    /** 位置・大きさを置ける範囲に収める。戻り値は (x, y, w, h) */
    fun clampPlacement(x: Int, y: Int, w: Int, h: Int): IntArray {
        val cw = w.coerceIn(widthRange)
        val ch = h.coerceIn(heightRange)
        return intArrayOf(x.coerceIn(xRange(cw)), y.coerceIn(yRange(ch)), cw, ch)
    }

    /** 位置が未設定のメーターに id と配置を与える (旧データの移行・初期データ用) */
    fun normalize(d: Dashboard): Dashboard {
        var nextId = (d.gauges.maxOfOrNull { it.id } ?: 0L) + 1
        val withIds = d.gauges.map { if (it.id == 0L) it.copy(id = nextId++) else it }
        // 以前の版の単位 (24 × 12 マス) なら細かい単位に直す
        val scaled = if (d.unitScale < SCALE) {
            val f = SCALE / d.unitScale.coerceAtLeast(1)
            withIds.map { g -> if (g.w > 0 && g.h > 0) g.copy(x = g.x * f, y = g.y * f, w = g.w * f, h = g.h * f) else g }
        } else withIds
        val placed = if (scaled.all { it.w > 0 && it.h > 0 }) scaled else arrangeInGrid(scaled, d.columns)
        return d.copy(gauges = placed, unitScale = SCALE)
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

    /** 配置のグループ (2 つ以上のメーターがあるものだけ)。グループ id → メーター */
    fun groups(gauges: List<GaugeConfig>): Map<Long, List<GaugeConfig>> =
        gauges.filter { it.layoutGroup != null }.groupBy { it.layoutGroup!! }.filterValues { it.size >= 2 }

    /** メーターをまとめて囲む範囲 (x, y, w, h) */
    fun bounds(gauges: List<GaugeConfig>): IntArray {
        val x0 = gauges.minOf { it.x }
        val y0 = gauges.minOf { it.y }
        val x1 = gauges.maxOf { it.x + it.w }
        val y1 = gauges.maxOf { it.y + it.h }
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    /** グループを (dx, dy) マス動かした配置。グループ全体がページの外へ出きらないように動きを抑える */
    fun moveGroup(gauges: List<GaugeConfig>, dx: Int, dy: Int): Map<Long, IntArray> {
        val b = bounds(gauges)
        val nx = (b[0] + dx).coerceIn(SCALE - b[2], COLS - SCALE)
        val ny = (b[1] + dy).coerceIn(SCALE - b[3], ROWS - SCALE)
        return gauges.associate { it.id to intArrayOf(it.x + nx - b[0], it.y + ny - b[1], it.w, it.h) }
    }

    /**
     * グループの囲む範囲を幅 [w]・高さ [h] にしたときの各メーターの配置 (左上は動かさず、相対位置を保って拡大縮小)。
     * どのメーターも [MIN_SIZE] より小さくならない大きさまでしか縮めない
     */
    fun resizeGroup(gauges: List<GaugeConfig>, w: Int, h: Int): Map<Long, IntArray> {
        val b = bounds(gauges)
        val minW = kotlin.math.ceil(b[2] * MIN_SIZE.toDouble() / gauges.minOf { it.w }).toInt()
        val minH = kotlin.math.ceil(b[3] * MIN_SIZE.toDouble() / gauges.minOf { it.h }).toInt()
        val nw = w.coerceIn(minOf(minW, b[2]), maxOf(COLS * 2, b[2]))
        val nh = h.coerceIn(minOf(minH, b[3]), maxOf(ROWS * 2, b[3]))
        val sx = nw.toDouble() / b[2]
        val sy = nh.toDouble() / b[3]
        fun sc(v: Int, s: Double) = kotlin.math.round(v * s).toInt()
        return gauges.associate { g ->
            val l = sc(g.x - b[0], sx)
            val t = sc(g.y - b[1], sy)
            val r = sc(g.x + g.w - b[0], sx)
            val bt = sc(g.y + g.h - b[1], sy)
            g.id to intArrayOf(b[0] + l, b[1] + t, maxOf(MIN_SIZE, r - l), maxOf(MIN_SIZE, bt - t))
        }
    }

    /**
     * コピーしたメーター [src] (1 つ以上) を [existing] のページに貼り付ける位置のずらし量 (dx, dy)。
     * 元と同じ位置が空いていればそこ、空いていなければ空いている場所、どこも空いていなければ 1 マスずらす
     */
    fun pasteOffset(existing: List<GaugeConfig>, src: List<GaugeConfig>): Pair<Int, Int> {
        val b = bounds(src)
        fun free(x: Int, y: Int) = existing.none { g ->
            x < g.x + g.w && g.x < x + b[2] && y < g.y + g.h && g.y < y + b[3]
        }
        fun fits(x: Int, y: Int) = x >= 0 && y >= 0 && x + b[2] <= COLS && y + b[3] <= ROWS
        if (free(b[0], b[1])) return 0 to 0
        val spot = findFreeSpot(existing, b[2], b[3])
        if (free(spot.first, spot.second) && fits(spot.first, spot.second)) return spot.first - b[0] to spot.second - b[1]
        val x = (b[0] + SCALE).coerceAtMost(COLS - b[2]).coerceAtLeast(0)
        val y = (b[1] + SCALE).coerceAtMost(ROWS - b[3]).coerceAtLeast(0)
        return x - b[0] to y - b[1]
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
        "赤" to 0xFFFF1A1A,
        "濃い赤" to 0xFFC62828,
        "淡い赤" to 0xFFEF5350,
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
