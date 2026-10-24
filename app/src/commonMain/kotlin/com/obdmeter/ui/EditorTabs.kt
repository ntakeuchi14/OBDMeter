package com.obdmeter.ui

import com.obdmeter.data.GaugeStyle

/** メーターの編集画面のタブ */
enum class EditorTab(val label: String) {
    ITEM("項目"),
    ITEM_AXIS("項目・軸"),
    SCALE("目盛り・針"),
    BAR("バーの形"),
    LAMP("点灯条件・色"),
    TABLE("並べ方・文字"),
    VIEW("表示"),
    RECORD("記録"),
    LOOK("色・数値"),
    IMAGE("画像"),
    PLACE("配置"),
}

/**
 * 表示形式ごとに、編集画面に出すタブと設定。その表示形式の描画が使わない設定は出さない
 * (表示形式を切り替えても、隠れた設定の値は消さずに残す)
 */
object EditorTabs {
    fun forStyle(style: GaugeStyle): List<EditorTab> = when (style) {
        GaugeStyle.ANALOG -> listOf(EditorTab.ITEM, EditorTab.SCALE, EditorTab.LOOK, EditorTab.IMAGE, EditorTab.PLACE)
        GaugeStyle.BAR -> listOf(EditorTab.ITEM, EditorTab.BAR, EditorTab.LOOK, EditorTab.IMAGE, EditorTab.PLACE)
        GaugeStyle.DIGITAL -> listOf(EditorTab.ITEM, EditorTab.LOOK, EditorTab.IMAGE, EditorTab.PLACE)
        GaugeStyle.LAMP -> listOf(EditorTab.ITEM, EditorTab.LAMP, EditorTab.PLACE)
        GaugeStyle.TABLE -> listOf(EditorTab.ITEM, EditorTab.TABLE, EditorTab.PLACE)
        GaugeStyle.CHART -> listOf(EditorTab.ITEM_AXIS, EditorTab.VIEW, EditorTab.RECORD, EditorTab.PLACE)
        GaugeStyle.MONITOR -> listOf(EditorTab.ITEM, EditorTab.VIEW, EditorTab.PLACE)
    }

    /** 表示形式を変えたとき、開いていたタブが無くなっていれば最初のタブにする */
    fun keepOrFirst(style: GaugeStyle, current: EditorTab): EditorTab =
        forStyle(style).let { tabs -> if (current in tabs) current else if (current == EditorTab.ITEM && EditorTab.ITEM_AXIS in tabs) EditorTab.ITEM_AXIS else tabs.first() }

    /** 目盛りの範囲 (最小・最大) を使う (目盛り・バー・グラフの軸・消えている 7 セグの桁) */
    fun usesRange(style: GaugeStyle): Boolean = style != GaugeStyle.MONITOR

    /** 警告値を使う (針・数値を赤くする、ランプの既定の点灯条件) */
    fun usesWarn(style: GaugeStyle): Boolean = style !in setOf(GaugeStyle.CHART, GaugeStyle.MONITOR)

    /** 表示名の欄を「項目」タブに出す (値テーブル・変動監視は項目の一覧で名前を変える) */
    fun usesLabelField(style: GaugeStyle): Boolean = style !in setOf(GaugeStyle.TABLE, GaugeStyle.MONITOR)

    /** 最大値 (ピーク値) を使う */
    fun usesPeak(style: GaugeStyle): Boolean = style in setOf(GaugeStyle.ANALOG, GaugeStyle.BAR, GaugeStyle.DIGITAL)

    /** 外周リングを使う */
    fun usesRing(style: GaugeStyle): Boolean = style in setOf(GaugeStyle.ANALOG, GaugeStyle.LAMP)

    /** 消えている 7 セグを使う */
    fun usesGhost(style: GaugeStyle): Boolean = style in setOf(GaugeStyle.ANALOG, GaugeStyle.BAR, GaugeStyle.DIGITAL, GaugeStyle.TABLE)

    /** メーターの色 (現在値の弧・バー、グラフの 1 本目の線、変動監視の回数) を使う */
    fun usesAccent(style: GaugeStyle): Boolean = style !in setOf(GaugeStyle.DIGITAL, GaugeStyle.LAMP, GaugeStyle.TABLE)
}
