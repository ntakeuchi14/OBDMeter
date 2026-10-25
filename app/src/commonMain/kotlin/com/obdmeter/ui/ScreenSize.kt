package com.obdmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdmeter.ui.gauges.GaugeColors

/** 再現する画面の大きさ (px) と倍率 (Android の density。1 dp が何 px か) */
data class ScreenSpec(val width: Int, val height: Int, val density: Float) {
    fun encode(): String = "${width}x${height}x${fmtDensity(density)}"

    companion object {
        fun parse(text: String): ScreenSpec? = text.split('x').takeIf { it.size == 3 }?.let {
            val w = it[0].toIntOrNull() ?: return null
            val h = it[1].toIntOrNull() ?: return null
            val d = it[2].toFloatOrNull() ?: return null
            if (w in 100..8000 && h in 100..8000 && d in 0.5f..4f) ScreenSpec(w, h, d) else null
        }
    }
}

private fun fmtDensity(d: Float): String {
    val t = (kotlin.math.round(d * 1000) / 1000.0).toString()
    return if (t.contains('.')) t.trimEnd('0').trimEnd('.') else t
}

/**
 * [spec] の大きさで画面を作り、使える領域に収まるよう縮小して中央に置く (拡大はしない)。
 * [spec] が null なら、そのまま全体に表示する。
 * [sideArrows] なら左右に矢印の場所を空け、[onArrow] があれば矢印を出す (-1 = 前のページ、+1 = 次のページ)
 */
@Composable
fun SizedScreen(
    spec: ScreenSpec?,
    sideArrows: Boolean = false,
    onArrow: ((Int) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val gutter = if (sideArrows) 48.dp else 0.dp
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (sideArrows) PageArrow(gutter, "‹", onArrow?.let { { it(-1) } })
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (spec == null) {
                Box(Modifier.fillMaxSize().background(GaugeColors.Background)) { content() }
                return@BoxWithConstraints
            }
            val outer = LocalDensity.current
            val fit = minOf(constraints.maxWidth.toFloat() / spec.width, constraints.maxHeight.toFloat() / spec.height, 1f)
            val wDp = with(outer) { (spec.width * fit).toDp() }
            val hDp = with(outer) { (spec.height * fit).toDp() }
            Box(Modifier.size(wDp, hDp).background(GaugeColors.Background).border(1.dp, Color(0xFF2A3038))) {
                CompositionLocalProvider(LocalDensity provides Density(spec.density * fit, outer.fontScale)) { content() }
            }
        }
        if (sideArrows) PageArrow(gutter, "›", onArrow?.let { { it(1) } })
    }
}

/** 画面の外のページめくりの矢印 ([onClick] が null なら何も出さない) */
@Composable
private fun PageArrow(width: androidx.compose.ui.unit.Dp, label: String, onClick: (() -> Unit)?) {
    Box(Modifier.width(width).fillMaxHeight(), contentAlignment = Alignment.Center) {
        if (onClick != null) {
            Box(
                Modifier.size(width - 8.dp, 96.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1F27))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = GaugeColors.Text, fontSize = 36.sp) }
        }
    }
}

private val PRESETS = listOf(
    ScreenSpec(1920, 720, 1f), ScreenSpec(1280, 480, 1f), ScreenSpec(1280, 720, 1f),
    ScreenSpec(1920, 1080, 1.5f), ScreenSpec(1024, 600, 1f), ScreenSpec(2400, 1080, 2.625f),
)

/** 設定の「画面サイズ」 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreenSizeSection(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val current = ScreenSpec.parse(s.screenSize)
    val set: (ScreenSpec?) -> Unit = { v -> vm.updateSettings { it.copy(screenSize = v?.encode().orEmpty()) } }
    var wText by remember(current) { mutableStateOf(current?.width?.toString() ?: "1920") }
    var hText by remember(current) { mutableStateOf(current?.height?.toString() ?: "720") }
    var dText by remember(current) { mutableStateOf(current?.density?.let { fmtDensity(it) } ?: "1") }
    val sub = GaugeColors.SubText
    deviceScreenInfo()?.let { Text(it, modifier = Modifier.padding(bottom = 4.dp)) }
    Text(
        "表示する画面の大きさ (px) と倍率を指定すると、その画面と同じ見え方になります (別の機種や車載ナビ向けのページを作るとき用)。" +
            "画面より大きいときは縮小して全体を表示します。" +
            if (isWebApp) "Android 版の 設定 → 画面サイズ に、その端末の値が出ています。" else "",
        color = sub, fontSize = 12.sp,
    )
    FlowRow(Modifier.padding(vertical = 6.dp)) {
        FilterChip(current == null, { set(null) }, { Text(if (isWebApp) "ウィンドウに合わせる" else "この端末のまま") },
            Modifier.padding(end = 6.dp))
        for (p in PRESETS) {
            FilterChip(current == p, { set(p) },
                { Text("${p.width}×${p.height}" + if (p.density != 1f) " (×${fmtDensity(p.density)})" else "") },
                Modifier.padding(end = 6.dp))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val num = KeyboardOptions(keyboardType = KeyboardType.Number)
        OutlinedTextField(wText, { wText = it.filter(Char::isDigit).take(4) }, Modifier.width(110.dp),
            label = { Text("幅 px") }, singleLine = true, keyboardOptions = num)
        Text(" × ")
        OutlinedTextField(hText, { hText = it.filter(Char::isDigit).take(4) }, Modifier.width(110.dp),
            label = { Text("高さ px") }, singleLine = true, keyboardOptions = num)
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(dText, { dText = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, Modifier.width(100.dp),
            label = { Text("倍率") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Spacer(Modifier.width(8.dp))
        val spec = ScreenSpec.parse("${wText}x${hText}x$dText")
        FilledTonalButton(enabled = spec != null, onClick = { set(spec) }) { Text("適用") }
    }
    Text(
        "倍率は Android の画面密度 (1 dp が何 px か) です。大きくすると文字やボタンが大きくなります。",
        color = sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
    )
}
