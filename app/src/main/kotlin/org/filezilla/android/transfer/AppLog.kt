package org.filezilla.android.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.LogLevel

/** One line of the protocol log, as the log screen shows it. */
data class LogLine(val level: LogLevel, val message: String, val atMillis: Long)

/**
 * The message log, kept the way FileZilla keeps it: every command and reply,
 * verbatim.
 *
 * When a transfer misbehaves against some unusual server, this trace is the
 * only thing that explains why -- which is exactly the situation this app
 * exists for, so it is a first-class screen rather than a debug build extra.
 *
 * Bounded, because a long transfer against a chatty server would otherwise
 * grow it without limit on a device that has no memory to spare.
 */
class AppLog(private val capacity: Int = 2_000) : FtpLogger {

    private val lines = ArrayDeque<LogLine>()
    private val state = MutableStateFlow<List<LogLine>>(emptyList())

    val log: StateFlow<List<LogLine>> = state.asStateFlow()

    override fun log(level: LogLevel, message: String) {
        val line = LogLine(level, message, System.currentTimeMillis())
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > capacity) lines.removeFirst()
            state.value = lines.toList()
        }
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            state.value = emptyList()
        }
    }
}
