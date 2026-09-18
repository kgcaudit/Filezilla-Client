package org.filezilla.ftp.listing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class MlsdParserTest {

    private fun utcOf(entry: DirectoryEntry) =
        Instant.ofEpochMilli(entry.time!!.epochMillis).atZone(ZoneOffset.UTC)

    @Test
    fun `parses a file entry`() {
        val e = MlsdParser.parse(
            "type=file;size=1234;modify=20240125031500;perm=adfr; report.pdf",
        )!!
        assertEquals("report.pdf", e.name)
        assertEquals(1234, e.size)
        assertFalse(e.isDirectory)
        assertEquals("adfr", e.permissions)
        val t = utcOf(e)
        assertEquals(2024, t.year)
        assertEquals(1, t.monthValue)
        assertEquals(25, t.dayOfMonth)
        assertEquals(3, t.hour)
        assertEquals(15, t.minute)
        assertEquals(TimeAccuracy.SECONDS, e.time!!.accuracy)
        assertTrue(e.hasTime)
    }

    @Test
    fun `parses a directory entry`() {
        val e = MlsdParser.parse("type=dir;sizd=4096;modify=20231101000000; my folder")!!
        assertEquals("my folder", e.name)
        assertTrue(e.isDirectory)
    }

    @Test
    fun `keeps spaces in the filename`() {
        val e = MlsdParser.parse("type=file;size=1; a file  with   spaces.txt")!!
        assertEquals("a file  with   spaces.txt", e.name)
    }

    @Test
    fun `rejects the current and parent directory entries`() {
        assertNull(MlsdParser.parse("type=cdir;modify=20240101000000; /pub"))
        assertNull(MlsdParser.parse("type=pdir;modify=20240101000000; /"))
    }

    @Test
    fun `reads a symlink and its target`() {
        val e = MlsdParser.parse("type=OS.unix=slink:/usr/bin;size=7; bin")!!
        assertEquals("bin", e.name)
        assertTrue(e.isLink)
        // A symlink to a file and one to a directory are indistinguishable
        // here, so both are treated as directories until CWD says otherwise.
        assertTrue(e.isDirectory)
        assertEquals("/usr/bin", e.linkTarget)
    }

    @Test
    fun `assembles owner and group whatever order the facts arrive in`() {
        val a = MlsdParser.parse("type=file;size=1;UNIX.groupname=staff;UNIX.ownername=alice; f")!!
        assertEquals("alice staff", a.ownerGroup)

        val b = MlsdParser.parse("type=file;size=1;UNIX.gid=100;UNIX.uid=1000; f")!!
        assertEquals("1000 100", b.ownerGroup)
    }

    @Test
    fun `prefers names over numeric ids`() {
        val e = MlsdParser.parse(
            "type=file;size=1;UNIX.uid=1000;UNIX.ownername=alice;UNIX.gid=100;UNIX.groupname=staff; f",
        )!!
        assertEquals("alice staff", e.ownerGroup)
    }

    @Test
    fun `combines perm and unix mode`() {
        val e = MlsdParser.parse("type=file;size=1;perm=adfr;UNIX.mode=0644; f")!!
        assertEquals("adfr (0644)", e.permissions)
    }

    @Test
    fun `takes the modify fact over create`() {
        val e = MlsdParser.parse(
            "type=file;size=1;create=20200101000000;modify=20240125031500; f",
        )!!
        assertEquals(2024, utcOf(e).year)
    }

    @Test
    fun `accepts fractional seconds in a timestamp`() {
        val e = MlsdParser.parse("type=file;size=1;modify=20240125031500.123; f")!!
        assertEquals(15, utcOf(e).minute)
    }

    @Test
    fun `rejects malformed lines outright`() {
        // Parsing is strict on purpose: a half-understood line is worse than a
        // skipped one, because the size or type would be silently wrong.
        assertNull(MlsdParser.parse(""))
        assertNull(MlsdParser.parse("type=file;size=1;"))            // no name
        assertNull(MlsdParser.parse("type=file;size=notanumber; f")) // bad size
        assertNull(MlsdParser.parse("type=file;size=-5; f"))         // negative size
        assertNull(MlsdParser.parse("typefile;size=1; f"))           // fact with no '='
        assertNull(MlsdParser.parse("type=file;modify=2024; f"))     // truncated time
    }
}
