package com.obdmeter.ui

import androidx.compose.ui.graphics.Color
import com.obdmeter.core.Condition
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
        arcWidth = arcWidth.coerceIn(0f, 3f),
        redZone = zone,
        redZoneColor = redZoneColor.color(GaugeColors.WarnZone),
        showNeedle = showNeedle,
        needleColor = needleColor.color(GaugeColors.Text),
        needleLength = needleLength.coerceIn(0.3f, 1.3f),
        needleWidth = needleWidth.coerceIn(0.2f, 4f),
        needleTail = needleTail.coerceIn(0f, 0.6f),
        knobColor = knobColor?.let { Color(it) },
        knobSize = knobSize.coerceIn(0f, 4f),
        ringThickness = ringThickness.coerceIn(0.2f, 4f),
        ringDepth = ringDepth.coerceIn(0f, 1f),
        ringInner = ringInner.coerceIn(0f, 0.8f),
        valueOffsetX = valueOffsetX.coerceIn(-0.95f, 0.95f),
        imageOffsetX = imageOffsetX.coerceIn(-0.6f, 0.6f),
        imageOffsetY = imageOffsetY.coerceIn(-0.6f, 0.6f),
        faceColor = faceColor?.let { Color(it) },
        faceScale = faceScale.coerceIn(0.2f, 1.5f),
        barAngle = barAngle.coerceIn(-180f, 180f),
        barSegments = barSegments.coerceIn(0, 60),
        barSegmentGap = barSegmentGap.coerceIn(0.05f, 0.8f),
        barArc = barArc,
        barTaper = barTaper.coerceIn(0f, 1f),
        barOffColor = barOffColor?.let { Color(it) },
        barZoneColors = barZoneColors,
        barMidColor = barMidColor.color(Color(0xFFFFC107)),
        barMidFrom = barMidFrom,
        glow = glow.coerceIn(0f, 1f),
        smoothing = smoothing.coerceIn(0f, 1f),
        showTicks = showTicks,
        arcRoundCap = arcRoundCap,
        trackColor = trackColor?.let { Color(it) },
        arcTipDot = arcTipDot,
        arcTipColor = arcTipColor.color(Color.White),
        ghostDigits = ghostDigits,
        ghostAlpha = ghostAlpha.coerceIn(0.03f, 0.6f),
        nameOffsetX = nameOffsetX.coerceIn(-0.6f, 0.6f),
        nameOffsetY = nameOffsetY.coerceIn(-0.6f, 0.6f),
        nameColor = nameColor.color(labelColor.color(GaugeColors.SubText)),
        unitOffsetX = unitOffsetX.coerceIn(-0.6f, 0.6f),
        unitOffsetY = unitOffsetY.coerceIn(-0.6f, 0.6f),
        unitColor = unitColor.color(labelColor.color(GaugeColors.SubText)),
        lampCondition = lampCondition.takeIf { it.isNotBlank() }?.let { runCatching { Condition.compile(it) }.getOrNull() },
        lampOnColor = lampOnColor.color(GaugeColors.Warn),
        lampOffColor = lampOffColor.color(Color(0xFF2A2F36)),
        chartSpanMs = chartSpanSec.coerceIn(60, 31 * 86_400) * 1000L,
        // 変化したときだけ記録するグラフは、値が続いていた区間を記録側で覚えているので、間隔は最短として扱う
        chartIntervalMs = if (recordOnChange) 1_000L
            else (recordIntervalSec.coerceIn(1f, 600f) * 1000).toLong(),
        chartSkipGaps = chartSkipGaps,
        chartStep = recordOnChange,
        legendPosition = legendPosition,
        legendAlign = legendAlign,
        legendScale = legendScale.coerceIn(0.4f, 3f),
        monitorWindow = monitorWindow.coerceIn(0, com.obdmeter.data.Monitor.WINDOWS.lastIndex),
        legendBgAlpha = legendBgAlpha.coerceIn(0f, 1f),
        tableMaxRows = tableMaxRows.coerceIn(1, 30),
        tableValueBg = tableValueBg?.let { Color(it) },
        tableValueImage = tableValueImage,
        tableNameRatio = tableNameRatio.coerceIn(0f, 0.7f),
        tableUnitRatio = tableUnitRatio.coerceIn(0f, 0.5f),
        tableUnitInline = tableUnitInline,
        tableNameInline = tableNameInline,
        tableNameSize = tableNameSize.coerceIn(0.3f, 3f),
        tableUnitSize = tableUnitSize.coerceIn(0.3f, 3f),
        tableMarginBottom = tableMarginBottom.coerceIn(0f, 0.6f),
        tableMarginRight = tableMarginRight.coerceIn(0f, 0.6f),
        chartColors = chartColors.map { c -> c?.let { Color(it) } },
        showPeak = showPeak,
        peakColor = peakColor.color(GaugeColors.Peak),
        showPeakValue = showPeakValue,
        showValue = showValue,
        // デジタル表示はメーター色を数値の色にする (以前からの動作)
        valueColor = valueColor.color(if (style == GaugeStyle.DIGITAL && color != null) accent else GaugeColors.Text),
        valuePosition = valuePosition.coerceIn(-0.9f, 0.9f),
        valueSize = valueSize.coerceIn(0.3f, 3f),
        showName = showName,
        showUnit = showUnit,
        image = backgroundImage,
        imageScale = imageScale.coerceIn(0.3f, 2f),
        valueFont = valueFont,
        labelFont = labelFont,
        showCard = showCard,
    )
}

fun GaugeConfig.look(def: PidDef): GaugeLook = design.toLook(def, style)
