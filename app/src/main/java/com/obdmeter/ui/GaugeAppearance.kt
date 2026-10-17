package com.obdmeter.ui

import androidx.compose.ui.graphics.Color
import com.obdmeter.core.PidDef
import com.obdmeter.core.PidCatalog
import com.obdmeter.data.GaugeConfig
import com.obdmeter.ui.gauges.GaugeColors

/**
 * メーターの設定 (表示名・範囲・警告値・桁数) を PID 定義に反映した、描画用の定義を返す。
 * 換算式などは元の定義のまま。
 */
fun GaugeConfig.resolvedDef(catalog: PidCatalog): PidDef? {
    val base = catalog.get(pidKey) ?: return null
    val d = design
    val min = d.min ?: base.min
    val max = (d.max ?: base.max).let { if (it > min) it else base.max.coerceAtLeast(min + 1) }
    return base.copy(
        shortName = d.label?.takeIf { it.isNotBlank() } ?: base.shortName,
        min = min,
        max = max,
        decimals = d.decimals ?: base.decimals,
        warnAbove = if (d.useDefaultWarn) base.warnAbove else d.warnAbove,
        warnBelow = if (d.useDefaultWarn) base.warnBelow else d.warnBelow,
    )
}

fun GaugeConfig.accentColor(): Color = design.color?.let { Color(it) } ?: GaugeColors.Accent

/** 色をユーザーが指定しているか */
val GaugeConfig.hasCustomColor: Boolean get() = design.color != null
