package com.obdmeter.session

import com.obdmeter.core.CommLogger
import com.obdmeter.util.DateFmt
import com.obdmeter.util.PlatformLock
import com.obdmeter.util.locked
import com.obdmeter.util.nowMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class LogLine(val id: Long, val time: String, val direction: CommLogger.Direction, val text: String)

/**
 * 通信ログを直近 [capacity] 行だけ保持する。実車での原因調査用。
 * 記録は 1 行足すだけの軽い処理。画面用の一覧のコピーは、ログ画面を開いている間だけ 0.2 秒ごとに作る
 */
class LogBuffer(private val capacity: Int = 800) : CommLogger {
    private val lines = ArrayDeque<LogLine>()
    private var nextId = 0L
    private val fmt = DateFmt("HH:mm:ss.SSS")
    private val lock = PlatformLock()

    /** ログの一覧 (集めている間だけ動く。変わったときだけ流す) */
    val snapshot: Flow<List<LogLine>> = flow {
        var sent = -1L
        while (true) {
            val (id, copy) = lock.locked { if (nextId == sent) sent to null else nextId to lines.toList() }
            if (copy != null) { emit(copy); sent = id }
            delay(200)
        }
    }

    var enabled = true

    override fun log(direction: CommLogger.Direction, text: String) {
        if (!enabled && direction != CommLogger.Direction.ERROR && direction != CommLogger.Direction.INFO) return
        val time = fmt.format(nowMs())
        lock.locked {
            lines.addLast(LogLine(nextId++, time, direction, text))
            while (lines.size > capacity) lines.removeFirst()
        }
    }

    fun clear() {
        lock.locked {
            lines.clear()
            nextId++
        }
    }

    fun asText(): String = lock.locked {
        lines.joinToString("\n") { "${it.time} ${it.direction.name.padEnd(5)} ${it.text}" }
    }
}
