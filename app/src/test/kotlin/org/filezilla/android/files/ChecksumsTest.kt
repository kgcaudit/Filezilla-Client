package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** That the fingerprints are the ones the rest of the world computes. */
class ChecksumsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun fileOf(text: String): File =
        temporary.newFile().apply { writeText(text) }

    @Test
    fun `the known vectors for abc match every algorithm`() {
        // The textbook test vectors: "abc" has one published hash per algorithm.
        val abc = fileOf("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Checksums.of(abc, Checksums.Algorithm.SHA256),
        )
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            Checksums.of(abc, Checksums.Algorithm.SHA1),
        )
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            Checksums.of(abc, Checksums.Algorithm.MD5),
        )
    }

    @Test
    fun `an empty file has the empty-input hash, not a blank`() {
        val empty = temporary.newFile()
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Checksums.of(empty, Checksums.Algorithm.SHA256),
        )
    }

    @Test
    fun `a stop partway gives nothing rather than a hash of half the file`() {
        val file = fileOf("anything at all")
        assertNull(Checksums.of(file, Checksums.Algorithm.SHA256, cancelled = { true }))
    }
}
