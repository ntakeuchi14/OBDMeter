package com.obdmeter.session

import com.obdmeter.core.CommLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogLine(val id: Long, val time: String, val direction: CommLogger.Direction, val text: String)

/**
 * 通信ログを直近 [capacity] 行だけ保持する。実車での原因調査用。
 * 記録は 1 行足すだけの軽い処理。画面用の一覧のコピーは、ログ画面を開いている間だけ 0.2 秒ごとに作る
 */
class LogBuffer(private val capacity: Int = 800) : CommLogger {
    private val lines = ArrayDeque<LogLine>()
    private var nextId = 0L
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** ログの一覧 (集めている間だけ動く。変わったときだけ流す) */
    val snapshot: Flow<List<LogLine>> = flow {
        var sent = -1L
        while (true) {
            val (id, copy) = synchronized(lines) { if (nextId == sent) sent to null else nextId to lines.toList() }
            if (copy != null) { emit(copy); sent = id }
            delay(200)
        }
    }

    @Volatile
    var enabled = true

    override fun log(direction: CommLogger.Direction, text: String) {
        if (!enabled && direction != CommLogger.Direction.ERROR && direction != CommLogger.Direction.INFO) return
        synchronized(lines) {
            lines.addLast(LogLine(nextId++, fmt.format(Date()), direction, text))
            while (lines.size > capacity) lines.removeFirst()
        }
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            nextId++
        }
    }

    fun asText(): String = synchronized(lines) {
        lines.joinToString("\n") { "${it.time} ${it.direction.name.padEnd(5)} ${it.text}" }
    }
}
