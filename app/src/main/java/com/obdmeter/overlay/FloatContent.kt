package com.obdmeter.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obdmeter.AppCore
import com.obdmeter.ui.GaugeView
import com.obdmeter.ui.gauges.GaugeColors
import com.obdmeter.ui.gauges.LocalFontLibrary
import com.obdmeter.ui.resolvedDef
import com.obdmeter.ui.statusColor

/**
 * フロート表示の中身。表示中のページのメーターを、アプリと同じ並び・同じデザインで描く。
 * タップで操作ボタン (ページ切替・アプリを開く・閉じる)、ダブルタップでアプリを開く。
 */
@Composable
fun FloatContent(
    core: AppCore,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onPage: (Int) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val settings by core.settings.collectAsState()
    val pages by core.dashboards.collectAsState()
    val catalog by core.catalog.collectAsState()
    val values by core.session.values.collectAsState()
    val supported by core.session.supported.collectAsState()
    val state by core.session.state.collectAsState()
    val fonts by core.fonts.collectAsState()
    val page = pages.firstOrNull { it.id == settings.floatPageId } ?: pages.firstOrNull()

    CompositionLocalProvider(
        LocalContentColor provides GaugeColors.Text,
        LocalFontLibrary provides fonts.associateBy { it.id },
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
            if (page != null) {
                val b = page.floatBounds()
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val cw = maxWidth / b[2]
                    val ch = maxHeight / b[3]
                    page.gauges.forEach { g ->
                        key(g.id) {
                            Box(
                                Modifier
                                    .offset(cw * (g.x - b[0]), ch * (g.y - b[1]))
                                    .size(cw * g.w, ch * g.h)
                                    .padding(2.dp)
                            ) { GaugeView(g, g.resolvedDef(catalog), values, supported) }
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
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CtrlButton("◀") { onPage(-1) }
                    Text(
                        page?.name ?: "",
                        Modifier.weight(1f).padding(horizontal = 4.dp),
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = GaugeColors.Text,
                    )
                    CtrlButton("▶") { onPage(1) }
                    Spacer(Modifier.size(6.dp))
                    CtrlButton("開く", onOpenApp)
                    Spacer(Modifier.size(6.dp))
                    CtrlButton("✕", onClose)
                }
                // 右下: ここをドラッグすると大きさを変えられる
                Text("◢", Modifier.align(Alignment.BottomEnd).padding(6.dp), fontSize = 20.sp, color = GaugeColors.Text)
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, color = GaugeColors.Text) }
}
