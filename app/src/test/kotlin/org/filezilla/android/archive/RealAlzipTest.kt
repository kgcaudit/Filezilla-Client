package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Archives made by ALZip itself.
 *
 * Everything else here is tested against files written from the format as
 * unalz describes it -- which proves the format was read correctly and
 * proves nothing about what the program that makes these actually writes.
 * These two came from ALZip, and they carry two things the written ones
 * did not:
 *
 *  - the file attribute is 0, not the 0x20 that means "file". Reading a
 *    file as a directory because a bit was missing would have shown an
 *    archive of empty folders.
 *  - the size fields are two bytes wide, not four. The descriptor's top
 *    nibble says which, and a reader that assumed four would have taken
 *    the name's first bytes for a size.
 *
 * Neither would have been caught by anything written from the spec by the
 * same hands that read it.
 */
class RealAlzipTest {

    private fun fixture(name: String) = File("src/test/resources/archives/$name")

    @Test
    fun `an archive alzip made lists its file`() {
        AlzArchive.open(fixture("alzip_nopass.alz")).use { alz ->
            val entry = alz.entries.single()
            assertEquals("alzip.txt", entry.path)
            assertEquals(5, entry.size)
            assertEquals(7, entry.compressedSize)
            assertFalse(entry.encrypted)
            assertNull(entry.unreadable)
        }
    }

    @Test
    fun `an attribute of zero is a file, not a folder`() {
        // ALZip writes 0 here. The directory bit is 0x10 and it is absent,
        // which is the whole test: a reader that asked "is this not 0x20,
        // so a folder?" would have turned the archive into empty folders.
        AlzArchive.open(fixture("alzip_nopass.alz")).use { alz ->
            assertFalse(alz.entries.single().isDirectory)
        }
    }

    @Test
    fun `the contents come out byte for byte`() {
        AlzArchive.open(fixture("alzip_nopass.alz")).use { alz ->
            val out = alz.open(alz.entries.single()).use { it.readBytes() }
            // What the real unalz extracts from the same file.
            assertEquals("alzip", String(out))
        }
    }

    @Test
    fun `a password alzip set opens the entry`() {
        AlzArchive.open(fixture("alzip_pass.alz")).use { alz ->
            val entry = alz.entries.single()
            assertTrue("alzip marked this encrypted", entry.encrypted)

            val out = alz.open(entry, "alzip".toCharArray()).use { it.readBytes() }
            assertEquals("alzip", String(out))
        }
    }

    @Test
    fun `a wrong password on a real archive is still refused`() {
        AlzArchive.open(fixture("alzip_pass.alz")).use { alz ->
            assertThrows(WrongPassword::class.java) {
                alz.open(alz.entries.single(), "nope".toCharArray()).use { it.readBytes() }
            }
        }
    }

    @Test
    fun `the two archives describe the same file`() {
        val plain = AlzArchive.open(fixture("alzip_nopass.alz")).use { it.entries.single() }
        val secret = AlzArchive.open(fixture("alzip_pass.alz")).use { it.entries.single() }

        // Same source file, same sizes, same moment -- the only difference
        // is the twelve bytes of encryption header in front of the data,
        // which the compressed size does not count.
        assertEquals(plain.path, secret.path)
        assertEquals(plain.size, secret.size)
        assertEquals(plain.compressedSize, secret.compressedSize)
        assertEquals(plain.modifiedMillis, secret.modifiedMillis)
    }
    @Test
    fun `a korean name alzip wrote reads as korean`() {
        // The bytes on disk are be cb c1 fd, which is CP949 for 알집 and
        // nothing at all in UTF-8. ALZ carries no flag to say which, so
        // there is nothing to detect -- unalz says the format is CP949 and
        // only CP949, and this is that claim meeting a real file.
        AlzArchive.open(fixture("alzip_korean.alz")).use { alz ->
            assertEquals("알집.txt", alz.entries.single().path)
            assertEquals("알집.txt", alz.entries.single().name)
        }
    }

    @Test
    fun `a korean named entry comes out with its contents`() {
        AlzArchive.open(fixture("alzip_korean.alz")).use { alz ->
            val out = alz.open(alz.entries.single()).use { it.readBytes() }
            // Six bytes: two Korean syllables in UTF-8, which is what the
            // file held rather than anything about the archive's own
            // encoding. The two are separate questions and this keeps them
            // apart -- a name read as CP949, contents passed through.
            assertEquals(6, out.size)
            assertEquals("알집", String(out, Charsets.UTF_8))
        }
    }

    @Test
    fun `a korean name survives a password too`() {
        AlzArchive.open(fixture("alzip_korean_pass.alz")).use { alz ->
            val entry = alz.entries.single()
            assertEquals("알집.txt", entry.path)
            assertTrue(entry.encrypted)

            val out = alz.open(entry, "1234".toCharArray()).use { it.readBytes() }
            assertEquals("알집", String(out, Charsets.UTF_8))
        }
    }

    @Test
    fun `a numeric password is read as its characters`() {
        // "1234" is four characters, not a number, and the key schedule
        // eats bytes. Worth a test of its own because it is exactly the
        // kind of password somebody sets.
        AlzArchive.open(fixture("alzip_korean_pass.alz")).use { alz ->
            assertThrows(WrongPassword::class.java) {
                alz.open(alz.entries.single(), "4321".toCharArray()).use { it.readBytes() }
            }
        }
    }

}
