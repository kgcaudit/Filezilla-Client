package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Reading a zip split across several files.
 *
 * Two shapes exist and both are read: a raw byte split (`name.zip.001`,
 * `.002`, ...) that is just the whole zip cut up, and a standard split
 * (`name.z01`, `.z02`, ... and `name.zip` last) whose directory sits in the
 * final part and addresses the rest by disk number. A file spanning the
 * parts is the real test -- it must come back exactly whatever part edge it
 * crosses.
 */
class ZipVolumeTest {

    private fun fixture(name: String) = File("src/test/resources/archives/$name")

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    // The big.bin the fixtures were built from, which spans the volumes.
    private val bigSha = "692b3adf139bc4a6a6f58836b7669a979cff9461"

    private fun check(first: File) {
        Archives.open(first).use { zip ->
            val big = zip.entries.first { it.path == "payload/big.bin" }
            assertEquals(150000L, big.size)
            assertEquals(
                "the file spanning the parts must come back exactly",
                bigSha,
                sha1(zip.open(big).use { it.readBytes() }),
            )
            val hello = zip.entries.first { it.path == "payload/hello.txt" }
            assertEquals("hello split zip\n", zip.open(hello).use { it.readBytes() }.toString(Charsets.UTF_8))
            // The nested file, proving the whole directory came through.
            val names = zip.entries.map { it.path }
            assertTrue("nested file missing; names=$names", "payload/sub/nested.txt" in names)
        }
    }

    @Test
    fun `a standard split zip reads across z01 z02 and the final zip`() {
        check(fixture("spanned.zip"))
    }

    @Test
    fun `a raw byte split zip reads across its numbered parts`() {
        check(fixture("raw.zip.001"))
    }

    @Test
    fun `a split zip is recognised and its parts gathered`() {
        assertTrue(ZipArchive.isSplitZip(fixture("spanned.zip")))
        assertTrue(ZipArchive.isSplitZip(fixture("raw.zip.001")))
        assertEquals(Archives.Kind.ZIP, Archives.kindOf(fixture("spanned.zip")))
        assertEquals(Archives.Kind.ZIP, Archives.kindOf(fixture("raw.zip.001")))

        assertEquals(
            listOf("spanned.z01", "spanned.z02", "spanned.zip"),
            ZipArchive.volumesOf(fixture("spanned.zip")).map { it.name },
        )
        assertEquals(
            listOf("raw.zip.001", "raw.zip.002", "raw.zip.003"),
            ZipArchive.volumesOf(fixture("raw.zip.001")).map { it.name },
        )
    }

    @Test
    fun `opening a standard split from one of its z parts finds the whole set`() {
        // Tapping a .z01 part opens the archive too, not only the .zip.
        assertEquals(
            listOf("spanned.z01", "spanned.z02", "spanned.zip"),
            ZipArchive.volumesOf(fixture("spanned.z01")).map { it.name },
        )
        check(fixture("spanned.z01"))
    }

    @Test
    fun `a lone zip is still a single-part archive`() {
        assertEquals(listOf("plain.zip"), ZipArchive.volumesOf(fixture("plain.zip")).map { it.name })
        assertTrue(!ZipArchive.isSplitZip(fixture("plain.zip")))
    }
}
