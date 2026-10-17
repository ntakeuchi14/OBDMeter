package com.obdmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdmeter.core.PidDef
import com.obdmeter.core.Pids
import com.obdmeter.data.ConnectionType
import com.obdmeter.data.Dashboard
import com.obdmeter.session.ConnectionState
import com.obdmeter.ui.gauges.GaugeCard
import com.obdmeter.ui.gauges.GaugeColors
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(vm: MainViewModel, onNavigate: (Screen) -> Unit) {
    val dashboards by vm.dashboards.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val values by vm.values.collectAsStateWithLifecycle()
    val rate by vm.rate.collectAsStateWithLifecycle()
    val supported by vm.supported.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val pager = rememberPagerState { dashboards.size }
    val scope = rememberCoroutineScope()

    // 表示中のページの項目だけを読み出す
    LaunchedEffect(pager.currentPage, dashboards) {
        vm.setActiveDashboard(dashboards.getOrNull(pager.currentPage))
    }

    Column(Modifier.fillMaxSize()) {
        // ---- 上部バー ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIndicator(connection, rate, Modifier.weight(1f))
            val mode = if (settings.connectionType == ConnectionType.SIMULATOR) "SIM" else settings.usbAdapter.name
            Text(mode, color = GaugeColors.SubText, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            if (connection is ConnectionState.Disconnected) {
                FilledTonalButton(onClick = vm::connect) { Text("接続") }
            } else {
                OutlinedButton(onClick = vm::disconnect) { Text("切断") }
            }
            IconButton(onClick = { onNavigate(Screen.EDIT) }) { Icon(Icons.Default.Edit, "メーター編集") }
            IconButton(onClick = { onNavigate(Screen.LOG) }) { Icon(Icons.AutoMirrored.Filled.List, "通信ログ") }
            IconButton(onClick = { onNavigate(Screen.SETTINGS) }) { Icon(Icons.Default.Settings, "設定") }
        }

        // ---- ダッシュボード (左右スワイプで切替) ----
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            dashboards.getOrNull(page)?.let { GaugeGrid(it, values, supported) }
        }

        // ---- ページ名タブ ----
        if (dashboards.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                dashboards.forEachIndexed { i, d ->
                    val selected = i == pager.currentPage
                    Text(
                        d.name,
                        color = if (selected) GaugeColors.Text else GaugeColors.SubText,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) GaugeColors.CardBorder else Color.Transparent)
                            .clickable { scope.launch { pager.animateScrollToPage(i) } }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(state: ConnectionState, rate: Double, modifier: Modifier = Modifier) {
    val (color, text) = when (state) {
        ConnectionState.Disconnected -> GaugeColors.SubText to "未接続"
        is ConnectionState.Connecting -> Color(0xFFFFC107) to state.message
        is ConnectionState.Connected -> Color(0xFF4CAF50) to "接続中  %.1f PID/s".format(rate)
        is ConnectionState.Error -> GaugeColors.Warn to (state.message + if (state.retrying) "  (再接続待ち)" else "")
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = GaugeColors.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 列数に合わせて画面いっぱいにメーターを並べる */
@Composable
private fun GaugeGrid(dashboard: Dashboard, values: Map<String, Double>, supported: Set<Int>?) {
    val items = dashboard.gauges.mapNotNull { g -> Pids.get(g.pidKey)?.let { it to g } }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("メーターがありません。右上の編集ボタンから追加してください", color = GaugeColors.SubText)
        }
        return
    }
    val cols = dashboard.columns.coerceIn(1, 4)
    val rows = items.chunked(cols)
    Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (def, cfg) ->
                    GaugeCard(
                        def = def,
                        value = values[def.key],
                        style = cfg.style,
                        unsupported = isUnsupported(def, supported),
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
                // 最終行が埋まらない場合は空きで揃える
                repeat(cols - row.size) { Spacer(Modifier.weight(1f).height(1.dp)) }
            }
        }
    }
}

fun isUnsupported(def: PidDef, supported: Set<Int>?): Boolean {
    if (supported == null) return false
    return when (def.kind) {
        PidDef.Kind.MODE01 -> def.pid !in supported
        PidDef.Kind.ADAPTER_VOLTAGE -> false
        PidDef.Kind.DERIVED -> def.deps.mapNotNull { Pids.get(it) }.any { isUnsupported(it, supported) }
    }
}
