package org.filezilla.android.archive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What can be checked of RAR without the native reader.
 *
 * The decoder is native and arm64-only, so its listing and extraction run
 * on a phone, not in this JVM. What lives here is the part that decides
 * whether a file is even offered to it: the signature test, which is pure
 * bytes. The real RAR5 language archive is the fixture, so a reader that
 * stopped recognising RAR would fail here rather than on someone's phone.
 */
class RarArchiveTest {

    private fun fixture(name: String) = File("src/test/resources/archives/$name")

    @Test
    fun `a rar is recognised by its signature, whatever the reader can do with it`() {
        assertTrue(RarArchive.looksLikeRar(fixture("rarlng.rar")))
        assertFalse(RarArchive.looksLikeRar(fixture("plain.zip")))
        assertFalse(RarArchive.looksLikeRar(fixture("plain.alz")))
        assertFalse(RarArchive.looksLikeRar(fixture("plain.egg")))
    }

    @Test
    fun `rar is a browsable archive extension`() {
        assertTrue(ArchiveNav.browsable("movie.rar"))
        assertTrue(Archives.looksLikeArchive("movie.RAR"))
    }
}
