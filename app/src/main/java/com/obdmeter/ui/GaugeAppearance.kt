package com.obdmeter.ui

import androidx.compose.ui.graphics.Color
import com.obdmeter.core.PidCatalog
import com.obdmeter.core.PidDef
import com.obdmeter.data.GaugeConfig
import com.obdmeter.data.GaugeDesign
import com.obdmeter.data.GaugeStyle
import com.obdmeter.ui.gauges.GaugeColors
import com.obdmeter.ui.gauges.GaugeLook

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

private fun Long?.color(default: Color): Color = this?.let { Color(it) } ?: default

/**
 * 保存されたデザイン設定を描画用の [GaugeLook] にする。
 * [def] は [resolvedDef] 済みのもの (範囲・警告値が反映されている)。
 */
fun GaugeDesign.toLook(def: PidDef, style: GaugeStyle): GaugeLook {
    val accent = color.color(GaugeColors.Accent)
    // 角度: 保存は「12 時 = 0°、時計回り」、描画は「3 時 = 0°」
    val s = startAngle.coerceIn(-360f, 360f)
    val e = endAngle.coerceIn(-360f, 360f)
    val sweep = (e - s).let { if (it <= 0f) it + 360f else it }.coerceIn(10f, 360f)

    val zone: ClosedFloatingPointRange<Double>? = when {
        !showRedZone -> null
        redZoneFrom != null || redZoneTo != null -> {
            val from = redZoneFrom ?: def.min
            val to = redZoneTo ?: def.max
            if (to > from) from..to else null
        }
        def.warnAbove != null -> def.warnAbove!!..def.max
        def.warnBelow != null -> def.min..def.warnBelow!!
        else -> null
    }

    return GaugeLook(
        accent = accent,
        startAngle = s - 90f,
        sweep = sweep,
        majorDivisions = majorDivisions?.takeIf { it in 1..40 },
        minorDivisions = minorDivisions.coerceIn(0, 9),
        showLabels = showLabels,
        tickRadius = tickRadius.coerceIn(0.3f, 1.2f),
        tickColor = tickColor.color(GaugeColors.Tick),
        labelColor = labelColor.color(GaugeColors.SubText),
        labelSize = labelSize.coerceIn(0.4f, 2.5f),
        ring = ring,
        ringColor = ringColor.color(GaugeColors.Ring),
        showTrack = showTrack,
        showValueArc = showValueArc,
        arcWidth = arcWidth.coerceIn(0.2f, 3f),
        redZone = zone,
        redZoneColor = redZoneColor.color(GaugeColors.WarnZone),
        showNeedle = showNeedle,
        needleColor = needleColor.color(GaugeColors.Text),
        needleLength = needleLength.coerceIn(0.3f, 1.3f),
        showValue = showValue,
        // デジタル表示はメーター色を数値の色にする (以前からの動作)
        valueColor = valueColor.color(if (style == GaugeStyle.DIGITAL && color != null) accent else GaugeColors.Text),
        valuePosition = valuePosition.coerceIn(-0.9f, 0.9f),
        valueSize = valueSize.coerceIn(0.3f, 3f),
        showName = showName,
        showUnit = showUnit,
        image = backgroundImage,
        imageScale = imageScale.coerceIn(0.3f, 2f),
        showCard = showCard,
    )
}

fun GaugeConfig.look(def: PidDef): GaugeLook = design.toLook(def, style)
