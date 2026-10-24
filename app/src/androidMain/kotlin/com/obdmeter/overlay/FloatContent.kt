package com.obdmeter.overlay

import androidx.compose.runtime.remember
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obdmeter.AppCore
import com.obdmeter.ui.GaugeView
import com.obdmeter.ui.gauges.GaugeColors
import com.obdmeter.ui.gauges.LocalFontLibrary
import com.obdmeter.ui.gauges.LocalHistory
import com.obdmeter.ui.resolvedDef
import com.obdmeter.ui.statusColor

/**
 * フロート表示のウィンドウ 1 つ (単独のメーター、またはグループのメーターをまとめたもの)。アプリと同じデザインで描く。
 * タップで操作ボタン (アプリを開く・閉じる)、ダブルタップでアプリを開く。
 */
@Composable
fun FloatGauge(
    core: AppCore,
    unitKey: Long,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val settings by core.settings.collectAsState()
    val pages by core.dashboards.collectAsState()
    val catalog by core.catalog.collectAsState()
    val values by core.values.collectAsState()
    val supported by core.session.supported.collectAsState()
    val state by core.session.state.collectAsState()
    val fonts by core.fonts.collectAsState()
    // このウィンドウに出すメーター (単独なら 1 つ、グループなら複数)
    val unitPage = pages.firstOrNull { it.floatUnits()[unitKey] != null }
    val members = unitPage?.floatUnits()?.get(unitKey).orEmpty()

    CompositionLocalProvider(
        LocalContentColor provides GaugeColors.Text,
        LocalFontLibrary provides fonts.associateBy { it.id },
        LocalHistory provides core.history,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = settings.floatOpacity.coerceIn(0.2f, 1f) }
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = settings.floatBackground.coerceIn(0f, 1f)))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onToggleControls() }, onDoubleTap = { onOpenApp() })
                }
        ) {
            if (members.isNotEmpty()) {
                // ダッシュボードと同じ並びで、囲む範囲をウィンドウいっぱいに広げて描く
                val b = members.cellBounds()
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val cw = maxWidth / b[2]
                    val ch = maxHeight / b[3]
                    members.forEach { g ->
                        key(g.id) {
                            val def = g.resolvedDef(catalog)
                            val analog = g.style == com.obdmeter.data.GaugeStyle.ANALOG && def != null
                            val needle = remember(g.id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
                            val place = Modifier
                                .offset(cw * (g.x - b[0]), ch * (g.y - b[1]))
                                .size(cw * g.w, ch * g.h)
                                .padding(2.dp)
                            Box(place) {
                                GaugeView(g, def, values, supported, noCard = true, catalog = catalog,
                                    historySlot = unitPage?.let { com.obdmeter.data.HistorySlot.of(it.id, g.id) },
                                    layer = if (analog) com.obdmeter.ui.gauges.GaugeLayer.BODY else com.obdmeter.ui.gauges.GaugeLayer.ALL,
                                    needleState = needle)
                            }
                            // アナログの針は、まとめて表示しているほかのメーターより手前に描く
                            if (analog) Box(Modifier.zIndex(1f).then(place)) {
                                GaugeView(g, def, values, supported, noCard = true,
                                    layer = com.obdmeter.ui.gauges.GaugeLayer.NEEDLE, needleState = needle)
                            }
                        }
                    }
                }
            }

            // 接続状態の点 (左上)
            Box(
                Modifier.align(Alignment.TopStart).padding(5.dp).size(7.dp)
                    .clip(CircleShape).background(statusColor(state))
            )

            if (showControls) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                Row(
                    Modifier.align(Alignment.TopEnd).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CtrlButton("開く", onOpenApp)
                    Spacer(Modifier.size(4.dp))
                    CtrlButton("✕", onClose)
                }
                // 右下: ここをドラッグすると大きさを変えられる
                Text("◢", Modifier.align(Alignment.BottomEnd).padding(4.dp), fontSize = 18.sp, color = GaugeColors.Text)
            }
        }
    }
}

@Composable
private fun CtrlButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(GaugeColors.Card.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 12.sp, color = GaugeColors.Text) }
}
