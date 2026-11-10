package com.obdmeter.data

import com.obdmeter.DisplayLight

/** ライト連動の判定 (画面は使わない) */
object GlowLinkRule {
    /** ライトが点いているか。連動しない設定・読めないときは null */
    fun lightOn(l: DisplayLight, s: AppSettings, carLight: Double? = null): Boolean? = when (s.glowLink) {
        GlowLink.CAR_LIGHT -> carLight?.let { it >= 0.5 }
        GlowLink.OFF -> null
        GlowLink.NIGHT_MODE -> l.nightMode
        GlowLink.BRIGHTNESS -> l.brightness?.let { it <= s.glowLinkBrightness }
        GlowLink.SYSTEM_VALUE -> if (s.glowLinkKey.isEmpty()) null else l.custom?.let { it == s.glowLinkValue }
    }
}
