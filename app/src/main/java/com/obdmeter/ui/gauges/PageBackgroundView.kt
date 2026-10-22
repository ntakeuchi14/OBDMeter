package com.obdmeter.ui.gauges

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.obdmeter.data.BgPattern
import com.obdmeter.data.PageBackground
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ページの背景 (色 → 柄 → 画像 の順に重ねる)。メーターはこの上に描くので、画像も必ずメーターより後ろになる
 */
@Composable
fun PageBackgroundView(bg: PageBackground, modifier: Modifier) {
    val image = rememberGaugeImage(bg.image)
    Canvas(modifier) {
        val base = bg.color?.let { Color(it) } ?: GaugeColors.Background
        drawRect(base)
        if (bg.pattern != BgPattern.NONE) {
            val pc = (bg.patternColor?.let { Color(it) } ?: autoPatternColor(base)).copy(alpha = bg.patternAlpha.coerceIn(0f, 1f))
            drawPattern(bg.pattern, pc, base, (24f * density * bg.patternScale.coerceIn(0.2f, 6f)))
        }
        if (image != null && image.width > 0) {
            val w = size.width * bg.imageWidth
            val h = if (bg.keepAspect) w * image.height / image.width else size.height * bg.imageHeight
            val cx = size.width * bg.imageX
            val cy = size.height * bg.imageY
            drawImage(
                image,
                dstOffset = IntOffset((cx - w / 2).roundToInt(), (cy - h / 2).roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                alpha = bg.imageAlpha.coerceIn(0f, 1f),
            )
        }
    }
}

/** 柄の色の既定: 暗い背景には白、明るい背景には黒 */
internal fun autoPatternColor(base: Color): Color = if (base.luminance() < 0.4f) Color.White else Color.Black

/** 柄を [s] (px) の大きさで敷き詰める */
internal fun DrawScope.drawPattern(p: BgPattern, c: Color, base: Color, s: Float) {
    val w = size.width
    val h = size.height
    when (p) {
        BgPattern.NONE -> {}
        BgPattern.GRID -> {
            val sw = maxOf(1f, s * 0.05f)
            var x = 0f
            while (x <= w) { drawLine(c, Offset(x, 0f), Offset(x, h), sw); x += s }
            var y = 0f
            while (y <= h) { drawLine(c, Offset(0f, y), Offset(w, y), sw); y += s }
        }
        BgPattern.CHECK -> {
            var row = 0
            var y = 0f
            while (y < h) {
                var x = if (row % 2 == 0) 0f else s
                while (x < w) { drawRect(c, Offset(x, y), Size(s, s)); x += s * 2 }
                y += s; row++
            }
        }
        BgPattern.STRIPE -> {
            val sw = s * 0.35f
            var x = -h
            while (x < w + h) { drawLine(c, Offset(x, h), Offset(x + h, 0f), sw); x += s }
        }
        BgPattern.DOT -> {
            val r = s * 0.12f
            var row = 0
            var y = s / 2
            while (y < h + s) {
                var x = if (row % 2 == 0) s / 2 else s
                while (x < w + s) { drawCircle(c, r, Offset(x, y)); x += s }
                y += s * 0.866f; row++
            }
        }
        BgPattern.CARBON -> {
            // 2×2 の織り目: 縦の糸と横の糸を交互に、それぞれ明暗のグラデーションで描く
            val t = s / 2
            var j = 0
            var y = 0f
            while (y < h) {
                var i = 0
                var x = 0f
                while (x < w) {
                    val vertical = (i + j) % 2 == 0
                    val brush = if (vertical) {
                        Brush.horizontalGradient(listOf(c.copy(alpha = c.alpha * 0.25f), c, c.copy(alpha = c.alpha * 0.25f)), x, x + t)
                    } else {
                        Brush.verticalGradient(listOf(c.copy(alpha = c.alpha * 0.15f), c.copy(alpha = c.alpha * 0.7f), c.copy(alpha = c.alpha * 0.15f)), y, y + t)
                    }
                    drawRect(brush, Offset(x, y), Size(t, t))
                    x += t; i++
                }
                y += t; j++
            }
        }
        BgPattern.HONEYCOMB -> {
            val r = s * 0.6f
            val hw = sqrt(3f) * r
            val sw = maxOf(1f, s * 0.05f)
            var row = 0
            var cy = 0f
            while (cy < h + r * 2) {
                var cx = if (row % 2 == 0) 0f else hw / 2
                while (cx < w + hw) {
                    val path = Path()
                    for (k in 0..5) {
                        val a = (PI / 3 * k - PI / 6).toFloat()
                        val px = cx + r * kotlin.math.cos(a)
                        val py = cy + r * sin(a)
                        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    drawPath(path, c, style = Stroke(sw))
                    cx += hw
                }
                cy += r * 1.5f; row++
            }
        }
        BgPattern.WOOD -> {
            // 木目: ゆるく波打つ線を、太さと濃さを変えながら重ねる (乱数は使わず毎回同じ模様)
            val step = maxOf(2f, s * 0.12f)
            var y = -s
            var k = 0
            while (y < h + s) {
                val path = Path()
                var x = 0f
                val amp = s * (0.25f + 0.2f * sin(k * 1.7f))
                val phase = k * 0.9f
                path.moveTo(0f, y)
                while (x <= w) {
                    val yy = y + amp * sin(x / (s * 6f) + phase) + amp * 0.4f * sin(x / (s * 1.9f) + phase * 2.3f)
                    path.lineTo(x, yy)
                    x += maxOf(4f, s * 0.25f)
                }
                val a = 0.35f + 0.65f * ((sin(k * 2.39f) + 1f) / 2f)
                drawPath(path, c.copy(alpha = c.alpha * a), style = Stroke(maxOf(1f, step * (0.3f + 0.7f * a))))
                y += step * (1.2f + (sin(k * 0.73f) + 1f))
                k++
            }
        }
    }
}
