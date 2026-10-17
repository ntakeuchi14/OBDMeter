package com.obdmeter.session

import com.obdmeter.core.CommLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogLine(val id: Long, val time: String, val direction: CommLogger.Direction, val text: String)

/** 通信ログを直近 [capacity] 行だけ保持する。実車での原因調査用。 */
class LogBuffer(private val capacity: Int = 800) : CommLogger {
    private val lines = ArrayDeque<LogLine>()
    private var nextId = 0L
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _snapshot = MutableStateFlow<List<LogLine>>(emptyList())
    val snapshot: StateFlow<List<LogLine>> = _snapshot.asStateFlow()

    @Volatile
    var enabled = true

    override fun log(direction: CommLogger.Direction, text: String) {
        if (!enabled && direction != CommLogger.Direction.ERROR && direction != CommLogger.Direction.INFO) return
        synchronized(lines) {
            lines.addLast(LogLine(nextId++, fmt.format(Date()), direction, text))
            while (lines.size > capacity) lines.removeFirst()
            _snapshot.value = lines.toList()
        }
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            _snapshot.value = emptyList()
        }
    }

    fun asText(): String = synchronized(lines) {
        lines.joinToString("\n") { "${it.time} ${it.direction.name.padEnd(5)} ${it.text}" }
    }
}
