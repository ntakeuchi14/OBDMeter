package com.obdmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obdmeter.core.PidCatalog
import com.obdmeter.data.GaugeConfig
import com.obdmeter.data.Layout
import com.obdmeter.ui.gauges.GaugeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.obdmeter.util.format

/** メーターの一覧・微調整パッドに出す名前 (項目の短い名前と表示形式) */
internal fun GaugeConfig.editLabel(catalog: PidCatalog): String {
    val name = design.label?.takeIf { it.isNotBlank() } ?: resolvedDef(catalog)?.shortName ?: pidKey
    return "$name (${style.label})"
}

/** マス目の単位 → マス (1/4 単位) の表示 */
private fun cells(v: Int): String {
    val whole = v / Layout.SCALE
    val rest = v % Layout.SCALE
    return if (rest == 0) "$whole" else "%.2f".format(v.toDouble() / Layout.SCALE).trimEnd('0')
}

/** 押すと 1 回、押し続けると繰り返す小さなボタン */
@Composable
private fun RepeatButton(text: String, onStep: () -> Unit, modifier: Modifier = Modifier) {
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(GaugeColors.CardBorder)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    step()
                    val job = scope.launch {
                        delay(400)
                        while (true) { step(); delay(70) }
                    }
                    waitForUpOrCancellation()
                    job.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 22.sp, color = GaugeColors.Text) }
}

/**
 * 微調整パッド: 選んだメーターを ↑↓←→ で 1/4 マスずつ動かす・大きさを変える (長押しで繰り返し)。
 * [onNudge] (dx, dy, 大きさを変えるか)
 */
@Composable
internal fun FineTunePad(
    gauge: GaugeConfig,
    catalog: PidCatalog,
    onNudge: (Int, Int, Boolean) -> Unit,
    onPlace: (Int, Int, Int, Int) -> Unit,
    onSettings: () -> Unit,
    onSwapSide: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var resize by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(false) }
    Column(
        modifier.width(200.dp).clip(RoundedCornerShape(14.dp))
            .background(GaugeColors.Background.copy(alpha = 0.94f))
            .border(1.dp, GaugeColors.Accent, RoundedCornerShape(14.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(gauge.editLabel(catalog), color = GaugeColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("左右", color = GaugeColors.SubText, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onSwapSide).padding(horizontal = 6.dp, vertical = 4.dp))
            Text("閉じる", color = GaugeColors.SubText, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!resize, { resize = false }, { Text("移動") })
            FilterChip(resize, { resize = true }, { Text("サイズ") })
        }
        RepeatButton("▲", { onNudge(0, -1, resize) })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            RepeatButton("◀", { onNudge(-1, 0, resize) })
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Text(if (resize) "大きさ" else "位置", color = GaugeColors.SubText, fontSize = 11.sp)
            }
            RepeatButton("▶", { onNudge(1, 0, resize) })
        }
        RepeatButton("▼", { onNudge(0, 1, resize) })
        Text(
            "X ${cells(gauge.x)}  Y ${cells(gauge.y)}\n幅 ${cells(gauge.w)}  高さ ${cells(gauge.h)} (マス)",
            color = GaugeColors.Text, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { input = true }.padding(4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { input = true }) { Text("数値で入力", fontSize = 12.sp) }
            TextButton(onClick = onSettings) { Text("設定", fontSize = 12.sp) }
        }
        Text("1 回で 1/4 マス (長押しで連続)", color = GaugeColors.SubText, fontSize = 10.sp)
    }
    if (input) PlacementDialog(gauge, onDismiss = { input = false }) { x, y, w, h -> onPlace(x, y, w, h); input = false }
}

/** 位置・大きさを数値で入れる (マス単位、1/4 マス刻み) */
@Composable
private fun PlacementDialog(gauge: GaugeConfig, onDismiss: () -> Unit, onOk: (Int, Int, Int, Int) -> Unit) {
    val init = listOf(gauge.x, gauge.y, gauge.w, gauge.h).map(::cells)
    val fields = remember { init.map { mutableStateOf(it) } }
    fun units(i: Int): Int? = fields[i].value.trim().replace(',', '.').toDoubleOrNull()?.let { kotlin.math.round(it * Layout.SCALE).toInt() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("位置と大きさ (マス)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("X (左端)", "Y (上端)", "幅", "高さ").forEachIndexed { i, label ->
                    OutlinedTextField(
                        fields[i].value, { fields[i].value = it }, label = { Text(label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Text("0.25 マス刻みです。ページは横 ${Layout.COLS / Layout.SCALE} マス × 縦 ${Layout.ROWS / Layout.SCALE} マス", color = GaugeColors.SubText, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = (0..3).map(::units)
                if (v.all { it != null }) {
                    val w = v[2]!!.coerceIn(Layout.widthRange)
                    val h = v[3]!!.coerceIn(Layout.heightRange)
                    onOk(v[0]!!.coerceIn(Layout.xRange(w)), v[1]!!.coerceIn(Layout.yRange(h)), w, h)
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/**
 * ページのオブジェクト一覧 (手前のものが上)。タップで選ぶ (隠れているものも)、▲▼ で重なり順、「隠す」で編集中だけ一時的に隠す
 */
@Composable
internal fun GaugeListPanel(
    gauges: List<GaugeConfig>,
    catalog: PidCatalog,
    focused: Long?,
    hidden: Set<Long>,
    onSelect: (GaugeConfig) -> Unit,
    onToggleHidden: (GaugeConfig) -> Unit,
    onMoveZ: (GaugeConfig, Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(270.dp).clip(RoundedCornerShape(14.dp))
            .background(GaugeColors.Background.copy(alpha = 0.94f))
            .border(1.dp, GaugeColors.CardBorder, RoundedCornerShape(14.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("オブジェクト一覧 (上ほど手前)", color = GaugeColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("閉じる", color = GaugeColors.SubText, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Spacer(Modifier.size(4.dp))
        val list = gauges.asReversed()
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(list, key = { _, g -> g.id }) { i, g ->
                val sel = g.id == focused
                val off = g.id in hidden
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) GaugeColors.Accent.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { onSelect(g) }.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        g.editLabel(catalog) + (if (g.layoutGroup != null) " ・グループ" else ""),
                        color = if (off) GaugeColors.SubText else GaugeColors.Text, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(if (off) "戻す" else "隠す", fontSize = 12.sp, color = if (off) GaugeColors.Warn else GaugeColors.SubText,
                        modifier = Modifier.clickable { onToggleHidden(g) }.padding(horizontal = 6.dp, vertical = 2.dp))
                    // 一覧は手前が上なので、▲ = 手前へ
                    Text("▲", fontSize = 15.sp, color = if (i > 0) GaugeColors.Text else GaugeColors.CardBorder,
                        modifier = Modifier.clickable(enabled = i > 0) { onMoveZ(g, 1) }.padding(horizontal = 6.dp, vertical = 2.dp))
                    Text("▼", fontSize = 15.sp, color = if (i < list.lastIndex) GaugeColors.Text else GaugeColors.CardBorder,
                        modifier = Modifier.clickable(enabled = i < list.lastIndex) { onMoveZ(g, -1) }.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Text("「隠す」は編集中だけ見えなくします (完了すると戻ります)。タップで選ぶと右のパッドで動かせます", color = GaugeColors.SubText, fontSize = 10.sp)
    }
}
