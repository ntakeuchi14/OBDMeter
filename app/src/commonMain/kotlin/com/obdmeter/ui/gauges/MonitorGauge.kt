package com.obdmeter.ui.gauges

import com.obdmeter.util.format

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obdmeter.data.Monitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 変動監視。各項目の値が変わった回数を、選んだ期間 (直近 1 時間〜24 か月) で多い順に並べ、
 * 現在の値 (最新の計測値 − 初回の計測値) と最後に変わった時刻を出す。上の期間の欄をタップで切り替え
 */
@Composable
internal fun MonitorGauge(
    series: List<ChartSeries>,
    look: GaugeLook,
    fonts: GaugeFontSet,
    interactive: Boolean,
    modifier: Modifier,
    /** このメーターの記録の置き場所 ([com.obdmeter.data.HistorySlot])。null = 記録を出さない */
    slot: String?,
) {
    val history = LocalHistory.current?.takeIf { slot != null }
    var confirmReset by remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (confirmReset && history != null && slot != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("記録をリセット") },
            text = {
                Text(
                    "このメーターの変動の記録 (全項目の回数・変化時刻・初回の計測値) をすべて消して、次の計測からやり直します。" +
                        "元に戻せません。必要なら先に「書出」で CSV に保存してください。"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmReset = false
                    scope.launch { history.clear(slot) }
                }) { Text("リセットする", color = GaugeColors.Warn) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmReset = false }) { Text("キャンセル") } },
        )
    }
    var window by remember(look.monitorWindow) { mutableIntStateOf(look.monitorWindow) }
    val keys = remember(series.map { it.def.key }) { series.map { it.def.key } }
    // 記録が増えたら読み直す (読み直しは 2 秒に 1 回まで)。期間の境目が動くので一定の間隔でも読み直す
    var revision by remember { mutableLongStateOf(0L) }
    LaunchedEffect(history) { history?.revision?.collect { r -> revision = r; delay(2_000) } }
    var tick by remember { mutableLongStateOf(0L) }
    // 短い期間 (10 分以下) を見ているときは、すぐに回数が減っていくので 2 秒ごとに読み直す
    val tickMs = if (Monitor.WINDOWS[window].first <= 600_000L) 2_000L else 30_000L
    LaunchedEffect(tickMs) { while (true) { delay(tickMs); tick++ } }
    val stats by produceState(emptyList<Monitor.Stat>(), keys, revision, tick, slot) {
        if (history != null && slot != null) value = history.monitorStats(slot, keys, com.obdmeter.util.nowMs())
    }
    val byKey = stats.associateBy { it.key }
    val ordered = Monitor.rank(keys.map { k -> byKey[k] ?: Monitor.Stat(k, null, null, null, emptyList()) }, window)
    val seriesByKey = series.associateBy { it.def.key }
    val timeFmt = remember { com.obdmeter.util.DateFmt("yyyy/MM/dd HH:mm:ss") }

    BoxWithConstraints(modifier.padding(4.dp)) {
        val base = (maxHeight.value / (keys.size + 2).coerceAtLeast(3) * 0.45f).coerceIn(9f, 22f)
        val nameSize = (base * look.labelSize).sp
        val valueSize = (base * 1.1f * look.valueSize).sp
        Column(Modifier.fillMaxWidth()) {
            // 期間の切り替え (アプリの画面では右に記録の書き出し・読み込み)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Monitor.WINDOWS.forEachIndexed { i, (_, label) ->
                    val sel = i == window
                    Text(
                        label,
                        color = if (sel) GaugeColors.Background else GaugeColors.SubText,
                        fontSize = (base * 0.85f).sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .background(if (sel) look.accent else GaugeColors.Track, RoundedCornerShape(6.dp))
                            .then(if (interactive) Modifier.clickable { window = i } else Modifier)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (interactive && history != null && slot != null) {
                ChartCsvButtons(history, slot, keys)
                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                CsvButton("リセット") { confirmReset = true }
            }
            }
            // 見出し
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val h = (base * 0.75f).sp
                Text("#", Modifier.width((base * 1.8f).dp), color = GaugeColors.SubText, fontSize = h)
                Text("項目", Modifier.weight(1f), color = GaugeColors.SubText, fontSize = h)
                Text("回数", Modifier.width((base * 3.5f).dp), color = GaugeColors.SubText, fontSize = h, textAlign = TextAlign.End)
                Text("現在の値", Modifier.weight(0.8f), color = GaugeColors.SubText, fontSize = h, textAlign = TextAlign.End)
                Text("最後の変化", Modifier.weight(1.2f).padding(start = 8.dp), color = GaugeColors.SubText, fontSize = h)
            }
            ordered.forEachIndexed { rank, st ->
                val s = seriesByKey[st.key] ?: return@forEachIndexed
                val cur = Monitor.currentValue(st, s.value)
                val count = st.count(window)
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${rank + 1}", Modifier.width((base * 1.8f).dp), color = GaugeColors.SubText, fontSize = nameSize,
                        fontFamily = fonts.textLabel)
                    Text(s.def.shortName, Modifier.weight(1f), color = look.labelColor, fontSize = nameSize,
                        fontFamily = fonts.textLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$count", Modifier.width((base * 3.5f).dp),
                        color = if (count > 0) look.accent else GaugeColors.SubText, fontSize = valueSize,
                        fontFamily = fonts.value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                        style = androidx.compose.ui.text.TextStyle(shadow = glowShadow(look.accent, look.glow, 30f)),
                    )
                    Text(
                        s.def.format(cur) + if (look.showUnit && cur != null) " ${s.def.unitText}" else "",
                        Modifier.weight(0.8f), color = look.valueColor, fontSize = valueSize, fontFamily = fonts.textValue,
                        textAlign = TextAlign.End, maxLines = 1,
                    )
                    Text(
                        st.lastChangeAt?.let { timeFmt.format(it) } ?: if (st.first == null) "記録なし" else "変化なし",
                        Modifier.weight(1.2f).padding(start = 8.dp), color = GaugeColors.SubText, fontSize = nameSize,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
