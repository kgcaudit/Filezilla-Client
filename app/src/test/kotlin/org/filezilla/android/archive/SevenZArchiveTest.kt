package org.filezilla.android.archive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What can be checked of 7z without the native reader.
 *
 * The decoder is native and arm64-only, so its listing and extraction run
 * on a phone, not in this JVM -- the same as RAR. What lives here is the
 * part that decides whether a file is even offered to it: the signature
 * test, which is pure bytes. A real LZMA2 7z is the fixture, so a reader
 * that stopped recognising 7z would fail here rather than on a phone.
 */
class SevenZArchiveTest {

    private fun fixture(name: String) = File("src/test/resources/archives/$name")

    @Test
    fun `a 7z is recognised by its signature, whatever the reader can do with it`() {
        assertTrue(SevenZArchive.looksLikeSevenZ(fixture("plain.7z")))
        assertFalse(SevenZArchive.looksLikeSevenZ(fixture("plain.zip")))
        assertFalse(SevenZArchive.looksLikeSevenZ(fixture("plain.alz")))
        assertFalse(SevenZArchive.looksLikeSevenZ(fixture("plain.egg")))
        assertFalse(SevenZArchive.looksLikeSevenZ(fixture("rarlng.rar")))
    }

    @Test
    fun `7z is a browsable archive extension`() {
        assertTrue(ArchiveNav.browsable("backup.7z"))
        assertTrue(Archives.looksLikeArchive("backup.7Z"))
    }
}
