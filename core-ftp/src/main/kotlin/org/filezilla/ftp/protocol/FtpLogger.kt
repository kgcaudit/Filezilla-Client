package org.filezilla.ftp.protocol

/** Severity levels matching FileZilla's message log. */
enum class LogLevel { STATUS, ERROR, COMMAND, REPLY, DEBUG }

/**
 * Sink for the protocol log.
 *
 * The app shows this verbatim, the way FileZilla's message log does: when a
 * transfer misbehaves against some unusual server, the command/reply trace is
 * the only thing that explains why.
 */
fun interface FtpLogger {
    fun log(level: LogLevel, message: String)

    companion object {
        val NONE = FtpLogger { _, _ -> }

        fun stdout(): FtpLogger = FtpLogger { level, message ->
            val marker = when (level) {
                LogLevel.COMMAND -> ">"
                LogLevel.REPLY -> "<"
                LogLevel.ERROR -> "!"
                else -> " "
            }
            println("$marker $message")
        }
    }
}
