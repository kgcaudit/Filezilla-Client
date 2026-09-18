package org.filezilla.android.transfer

import org.filezilla.ftp.protocol.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogTest {

    @Test
    fun `the log is bounded so a long transfer cannot exhaust memory`() {
        val log = AppLog(capacity = 10)
        repeat(100) { log.log(LogLevel.COMMAND, "LIST $it") }

        val lines = log.log.value
        assertEquals(10, lines.size)
        // The tail is what is kept: the last thing that happened is what
        // explains a failure.
        assertEquals("LIST 99", lines.last().message)
        assertEquals("LIST 90", lines.first().message)
    }

    @Test
    fun `clearing empties the log`() {
        val log = AppLog(capacity = 10)
        log.log(LogLevel.STATUS, "connected")
        log.clear()
        assertEquals(emptyList<LogLine>(), log.log.value)
    }
}
