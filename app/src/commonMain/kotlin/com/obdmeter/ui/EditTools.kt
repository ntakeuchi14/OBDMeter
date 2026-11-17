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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
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
private fun cells(v: Int): String =
    if (v % Layout.SCALE == 0) "${v / Layout.SCALE}" else "%.4f".format(v.toDouble() / Layout.SCALE).trimEnd('0')

/** 微調整の 1 回の動き (単位) の選択肢 */
private val NUDGE_STEPS = listOf(Layout.SCALE / 16 to "1/16", Layout.SCALE / 4 to "1/4", Layout.SCALE to "1 マス")

/** 小さな切り替えボタン */
@Composable
private fun Seg(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, fontSize = 12.sp, color = if (on) GaugeColors.Background else GaugeColors.Text,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (on) GaugeColors.Accent else GaugeColors.CardBorder)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 押すと 1 回、押し続けると繰り返す小さなボタン */
@Composable
private fun RepeatButton(text: String, onStep: () -> Unit, modifier: Modifier = Modifier) {
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(GaugeColors.CardBorder)
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
    var step by remember { mutableStateOf(NUDGE_STEPS.first().first) }
    // 低い画面でも見切れないよう、十字キーと操作を横に並べる (それでも入らなければスクロール)
    Row(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(GaugeColors.Background.copy(alpha = 0.94f))
            .border(1.dp, GaugeColors.Accent, RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatButton("▲", { onNudge(0, -step, resize) })
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RepeatButton("◀", { onNudge(-step, 0, resize) })
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Text(if (resize) "大きさ" else "位置", color = GaugeColors.SubText, fontSize = 11.sp)
                }
                RepeatButton("▶", { onNudge(step, 0, resize) })
            }
            RepeatButton("▼", { onNudge(0, step, resize) })
        }
        Column(Modifier.width(170.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(gauge.editLabel(catalog), color = GaugeColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("閉じる", color = GaugeColors.SubText, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 4.dp, vertical = 2.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Seg("移動", !resize) { resize = false }
                Seg("サイズ", resize) { resize = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("刻み", color = GaugeColors.SubText, fontSize = 11.sp)
                NUDGE_STEPS.forEach { (u, label) -> Seg(label, step == u) { step = u } }
            }
            Text(
                "X ${cells(gauge.x)}  Y ${cells(gauge.y)}\n幅 ${cells(gauge.w)}  高さ ${cells(gauge.h)} (マス)",
                color = GaugeColors.Text, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { input = true }.padding(2.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Seg("設定", false, onSettings)
                Seg("数値で入力", false) { input = true }
                Seg("左右", false, onSwapSide)
            }
        }
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
                Text("1/16 マス (0.0625) 刻みです。ページは横 ${Layout.COLS / Layout.SCALE} マス × 縦 ${Layout.ROWS / Layout.SCALE} マス", color = GaugeColors.SubText, fontSize = 12.sp)
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
    onSettings: (GaugeConfig) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** チェックしたメーター (グループにする・解除する) */
    checked: Set<Long> = emptySet(),
    onCheck: (GaugeConfig) -> Unit = {},
    onGroup: () -> Unit = {},
    onUngroup: () -> Unit = {},
    onClearChecks: () -> Unit = {},
) {
    // グループごとの番号 (一覧で同じグループが分かるように)
    val groupNo = remember(gauges) {
        Layout.groups(gauges).keys.sorted().withIndex().associate { (i, gid) -> gid to i + 1 }
    }
    Column(
        modifier.width(300.dp).clip(RoundedCornerShape(14.dp))
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
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
            itemsIndexed(list, key = { _, g -> g.id }) { i, g ->
                val sel = g.id == focused
                val off = g.id in hidden
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) GaugeColors.Accent.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { onSelect(g) }.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // チェック (複数選んでグループにする)
                    val on = g.id in checked
                    Box(
                        Modifier.padding(end = 6.dp).size(20.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (on) GaugeColors.Accent else Color.Transparent)
                            .border(1.5.dp, if (on) GaugeColors.Accent else GaugeColors.SubText, RoundedCornerShape(4.dp))
                            .clickable { onCheck(g) },
                        contentAlignment = Alignment.Center,
                    ) { if (on) Text("✓", color = GaugeColors.Background, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Text(
                        g.editLabel(catalog) + (g.layoutGroup?.let { groupNo[it] }?.let { " ・G$it" } ?: ""),
                        color = if (off) GaugeColors.SubText else GaugeColors.Text, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text("設定", fontSize = 12.sp, color = GaugeColors.Accent,
                        modifier = Modifier.clickable { onSettings(g) }.padding(horizontal = 4.dp, vertical = 2.dp))
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
        if (checked.isNotEmpty()) {
            val anyGrouped = gauges.any { it.id in checked && it.layoutGroup != null && it.layoutGroup in groupNo }
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${checked.size} 個", color = GaugeColors.Text, fontSize = 12.sp)
                Box(Modifier.alpha(if (checked.size >= 2) 1f else 0.4f)) {
                    TextButton(onClick = onGroup, enabled = checked.size >= 2) { Text("グループにする", fontSize = 12.sp) }
                }
                Box(Modifier.alpha(if (anyGrouped) 1f else 0.4f)) {
                    TextButton(onClick = onUngroup, enabled = anyGrouped) { Text("解除", fontSize = 12.sp) }
                }
                TextButton(onClick = onClearChecks) { Text("やめる", fontSize = 12.sp) }
            }
        }
        Text("左の枠にチェックして複数選ぶとグループにできます。名前をタップで選んでパッドで微調整、「設定」で中身の編集。「隠す」は編集中だけ見えなくします", color = GaugeColors.SubText, fontSize = 10.sp)
    }
}
