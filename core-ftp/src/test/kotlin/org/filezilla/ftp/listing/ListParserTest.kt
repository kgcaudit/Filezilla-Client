package org.filezilla.ftp.listing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Cases taken from FileZilla's own parser test suite (`tests/dirparsertest.cpp`),
 * restricted to the Unix and DOS forms this port covers.
 */
class ListParserTest {

    // Fixed so the "no year given" inference is deterministic.
    private val clock: Clock = Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneOffset.UTC)

    private fun parse(line: String) = ListParser.parseLine(line, clock)

    private fun utcOf(entry: DirectoryEntry) =
        Instant.ofEpochMilli(entry.time!!.epochMillis).atZone(ZoneOffset.UTC)

    // ------------------------------------------------------------------ unix

    @Test
    fun `parses a standard unix directory line`() {
        val e = parse("dr-xr-xr-x   2 root     other        512 Apr  8  1994 01-unix-std dir")!!
        assertEquals("01-unix-std dir", e.name)
        assertEquals(512, e.size)
        assertTrue(e.isDirectory)
        assertEquals("dr-xr-xr-x", e.permissions)
        assertEquals("root other", e.ownerGroup)
        val t = utcOf(e)
        assertEquals(1994, t.year)
        assertEquals(4, t.monthValue)
        assertEquals(8, t.dayOfMonth)
        // A year-only line carries no time of day, so MDTM is still needed.
        assertEquals(TimeAccuracy.DAYS, e.time!!.accuracy)
        assertFalse(e.hasTime)
    }

    @Test
    fun `parses a unix file line with a time instead of a year`() {
        val e = parse("-rw-r--r--   1 root     other        531 Jan 25 00:17 recent file")!!
        assertEquals("recent file", e.name)
        assertEquals(531, e.size)
        assertFalse(e.isDirectory)
        val t = utcOf(e)
        // January is behind June, so it is this year.
        assertEquals(2024, t.year)
        assertEquals(1, t.monthValue)
        assertEquals(25, t.dayOfMonth)
        assertEquals(0, t.hour)
        assertEquals(17, t.minute)
        assertTrue(e.hasTime)
    }

    @Test
    fun `a date ahead of today belongs to last year`() {
        val e = parse("-rw-r--r--   1 root     other        531 Nov 20 08:30 older file")!!
        assertEquals(2023, utcOf(e).year)
    }

    @Test
    fun `handles a missing group`() {
        val e = parse("dr-xr-xr-x   2 root                  512 Apr  8  1994 03-unix-nogroup dir")!!
        assertEquals("03-unix-nogroup dir", e.name)
        assertEquals(512, e.size)
        assertEquals("root", e.ownerGroup)
    }

    @Test
    fun `reads a symlink and splits off its target`() {
        val e = parse("lrwxrwxrwx   1 root     other          7 Jan 25 00:17 04-unix-std link -> usr/bin")!!
        assertEquals("04-unix-std link", e.name)
        assertEquals("usr/bin", e.linkTarget)
        assertTrue(e.isLink)
        assertTrue(e.isDirectory)
    }

    @Test
    fun `parses the long-iso time style`() {
        val e = parse("-rw-r--r--   1 root     other        531 2024-01-25 00:17 iso file")!!
        assertEquals("iso file", e.name)
        val t = utcOf(e)
        assertEquals(2024, t.year)
        assertEquals(1, t.monthValue)
        assertEquals(25, t.dayOfMonth)
        assertEquals(17, t.minute)
    }

    @Test
    fun `strips the ls -F type marker from a name`() {
        val e = parse("drwxr-xr-x   2 root     other        512 Apr  8  1994 somedir/")!!
        assertEquals("somedir", e.name)
    }

    @Test
    fun `keeps spaces inside a filename`() {
        val e = parse("-rw-r--r--   1 root     other        531 Jan 25 00:17 a file  with  spaces.txt")!!
        assertEquals("a file  with  spaces.txt", e.name)
    }

    @Test
    fun `skips the total line and the dot entries`() {
        assertNull(parse("total 12"))
        assertNull(parse("drwxr-xr-x   2 root     other        512 Apr  8  1994 ."))
        assertNull(parse("drwxr-xr-x   2 root     other        512 Apr  8  1994 .."))
    }

    // ------------------------------------------------------------------- dos

    @Test
    fun `parses a dos directory line`() {
        val e = parse("04-27-00  12:09PM       <DIR>          Chatbot")!!
        assertEquals("Chatbot", e.name)
        assertTrue(e.isDirectory)
        val t = utcOf(e)
        assertEquals(2000, t.year)
        assertEquals(4, t.monthValue)
        assertEquals(27, t.dayOfMonth)
        assertEquals(12, t.hour)
        assertEquals(9, t.minute)
    }

    @Test
    fun `parses a dos file line`() {
        val e = parse("04-27-00  12:09AM               123456 report.txt")!!
        assertEquals("report.txt", e.name)
        assertEquals(123456, e.size)
        assertFalse(e.isDirectory)
        assertEquals(0, utcOf(e).hour)   // 12 AM is midnight
    }

    @Test
    fun `handles a dos line with a four digit year and 24 hour time`() {
        val e = parse("10-05-2023  17:42               4096 data.bin")!!
        assertEquals("data.bin", e.name)
        val t = utcOf(e)
        assertEquals(2023, t.year)
        assertEquals(17, t.hour)
    }

    @Test
    fun `maps a two digit year onto the right century`() {
        assertEquals(1999, utcOf(parse("04-27-99  12:09PM  <DIR>  d")!!).year)
        assertEquals(2000, utcOf(parse("04-27-00  12:09PM  <DIR>  d")!!).year)
    }

    @Test
    fun `rejects lines no parser understands`() {
        assertNull(parse(""))
        assertNull(parse("garbage"))
        assertNull(parse("xrw-r--r--   1 root other 531 Jan 25 00:17 bad perms"))
        assertNull(parse("-rw-r--r--   1 root other 531 Foo 25 00:17 bad month"))
    }
}
