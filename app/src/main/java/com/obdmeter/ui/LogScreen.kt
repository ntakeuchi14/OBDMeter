package com.obdmeter.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdmeter.core.CommLogger
import com.obdmeter.ui.gauges.GaugeColors

/** 送受信の生データを表示する。実車でうまく動かないときの調査用 (共有ボタンでテキスト送信可)。 */
@Composable
fun LogScreen(vm: MainViewModel, onBack: () -> Unit) {
    val lines by vm.log.snapshot.collectAsStateWithLifecycle(initialValue = emptyList())
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("通信ログ", onBack) {
            Text("自動スクロール", fontSize = 12.sp, color = GaugeColors.SubText)
            Switch(follow, { follow = it }, Modifier.padding(horizontal = 4.dp))
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "OBD Meter 通信ログ")
                    putExtra(Intent.EXTRA_TEXT, vm.log.asText())
                }
                context.startActivity(Intent.createChooser(send, "ログを共有"))
            }) { Icon(Icons.Default.Share, "共有") }
            IconButton(onClick = { vm.log.clear() }) { Icon(Icons.Default.Delete, "クリア") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState) {
            items(lines, key = { it.id }) { l ->
                val color = when (l.direction) {
                    CommLogger.Direction.TX -> GaugeColors.Accent
                    CommLogger.Direction.RX -> Color(0xFF81C784)
                    CommLogger.Direction.INFO -> GaugeColors.Text
                    CommLogger.Direction.ERROR -> GaugeColors.Warn
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "${l.time.substring(3)} ${l.direction.name.padEnd(5)} ",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GaugeColors.SubText,
                    )
                    Text(l.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
                }
            }
        }
    }
}
