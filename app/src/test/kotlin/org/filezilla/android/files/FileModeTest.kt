package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading permissions back out of what a server said.
 *
 * Worth this many cases because the value is about to be written back. A
 * mode read wrongly is not a wrong label on a dialog -- it is the mode the
 * server gets when the dialog is confirmed, and the most likely way to
 * lose access to your own files is an app that offered you 000 because it
 * could not read 755.
 */
class FileModeTest {

    private fun of(text: String?) = FileMode.of(text)

    @Test
    fun `the ls column that LIST sends`() {
        assertEquals("755", of("drwxr-xr-x").toString())
        assertEquals("644", of("-rw-r--r--").toString())
        assertEquals("777", of("-rwxrwxrwx").toString())
        assertEquals("000", of("----------").toString())
    }

    @Test
    fun `each bit lands under the right owner`() {
        // The one that catches an off-by-one in the column arithmetic: no
        // two of these are the same digit, so a shifted read cannot happen
        // to agree.
        assertEquals("421", of("-r---w---x").toString())
        assertEquals("124", of("---x-w-r--").toString())
    }

    @Test
    fun `the octal that MLSD sends`() {
        assertEquals("755", of("0755").toString())
        assertEquals("644", of("644").toString())
    }

    @Test
    fun `the perm fact is not a mode`() {
        // RFC 3659's perm says what this login may do with the file. It is
        // letters, it is not permissions, and reading it as a mode would
        // put a number on the dialog that was never the file's.
        assertNull(of("fdelcmp"))
        assertNull(of("adfr"))
    }

    @Test
    fun `a server that sends both is read from the octal`() {
        assertEquals("755", of("fdelcmp (0755)").toString())
        assertEquals("755", of("0755 (fdelcmp)").toString())
    }

    @Test
    fun `nothing to read stays nothing`() {
        assertNull(of(null))
        assertNull(of(""))
        assertNull(of("   "))
    }

    @Test
    fun `a special bit says whether execute is set with it`() {
        // Lower case s and t mean the special bit is set and so is
        // execute; upper case means the special bit is set and execute is
        // not. Reading them all as "not execute" shows the wrong mode and
        // then writes it: drwxrwxrwt is /tmp, and turning it into 776
        // takes away the bit that lets anyone enter.
        assertEquals("755", of("-rwsr-xr-x").toString())
        assertEquals("655", of("-rwSr-xr-x").toString())
        assertEquals("777", of("drwxrwxrwt").toString())
        assertEquals("776", of("drwxrwxrwT").toString())
    }

    @Test
    fun `a special bit in the ls column is kept aside too`() {
        // Same loss as a dropped leading digit, arriving by the other
        // shape: chmod 777 on /tmp is how a shared folder stops being
        // safe to share.
        assertEquals("1", FileMode.extraDigitIn("drwxrwxrwt"))
        assertEquals("4", FileMode.extraDigitIn("-rwsr-xr-x"))
        assertEquals("2", FileMode.extraDigitIn("-rwxr-sr-x"))
        assertEquals("7", FileMode.extraDigitIn("-rwsr-sr-t"))
        assertNull(FileMode.extraDigitIn("-rw-r--r--"))
    }

    @Test
    fun `a leading digit is kept aside rather than lost`() {
        // 1777 is /tmp: sticky, everyone may write. Editing group read on
        // it must not turn it into plain 777.
        assertEquals("777", of("1777").toString())
        assertEquals("1", FileMode.extraDigitIn("1777"))
        assertNull(FileMode.extraDigitIn("0755"))
        assertNull(FileMode.extraDigitIn("755"))
        assertNull(FileMode.extraDigitIn("drwxr-xr-x"))
    }

    @Test
    fun `toggling one bit leaves the others alone`() {
        val start = FileMode(0b111_101_101)

        val noGroupRead = start.with(FileMode.Who.GROUP, FileMode.What.READ, false)
        assertEquals("715", noGroupRead.toString())

        val backAgain = noGroupRead.with(FileMode.Who.GROUP, FileMode.What.READ, true)
        assertEquals(start, backAgain)
    }

    @Test
    fun `setting a bit that is already set changes nothing`() {
        val start = FileMode(0b111_101_101)
        assertEquals(start, start.with(FileMode.Who.OWNER, FileMode.What.WRITE, true))
    }

    @Test
    fun `what is allowed matches what the digits say`() {
        val mode = FileMode(0b110_100_000)

        assertEquals(true, mode.allows(FileMode.Who.OWNER, FileMode.What.WRITE))
        assertEquals(false, mode.allows(FileMode.Who.GROUP, FileMode.What.WRITE))
        assertEquals(true, mode.allows(FileMode.Who.GROUP, FileMode.What.READ))
        assertEquals(false, mode.allows(FileMode.Who.EVERYONE, FileMode.What.READ))
    }

    @Test
    fun `letters and digits describe the same thing`() {
        for (bits in 0..511) { // every three-digit mode, 000 to 777
            val mode = FileMode(bits)
            assertEquals(
                "a mode and its letters disagreed at ${mode}",
                mode,
                FileMode.of(mode.asLetters()),
            )
        }
    }

    @Test
    fun `every mode survives a round trip through its own digits`() {
        for (bits in 0..511) { // every three-digit mode, 000 to 777
            assertEquals(FileMode(bits), FileMode.of(FileMode(bits).toString()))
        }
    }

    @Test
    fun `the defaults are the ones a file and a folder want`() {
        assertEquals("644", FileMode.FILE.toString())
        // A folder with no execute cannot be entered, which makes 644 on a
        // folder a way to lock yourself out of it.
        assertEquals("755", FileMode.FOLDER.toString())
    }
}
