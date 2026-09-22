package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Finding the parts of a split archive, and which of them opens the set.
 *
 * The reading across the parts is native and runs on a phone; what is here
 * is the naming -- which files belong to one archive, and where it starts --
 * which is pure and decided before the reader is ever handed a path.
 */
class MultiVolumeTest {

    @get:Rule
    val dir = TemporaryFolder()

    private fun touch(name: String): File = dir.newFile(name)

    @Test
    fun `a split 7z gathers its parts in order from the first`() {
        val one = touch("holiday.7z.001")
        touch("holiday.7z.002")
        touch("holiday.7z.003")
        assertEquals(
            listOf("holiday.7z.001", "holiday.7z.002", "holiday.7z.003"),
            SevenZipNative.volumesOf(one).map { File(it).name },
        )
    }

    @Test
    fun `a split 7z stops at the first gap`() {
        val one = touch("movie.7z.001")
        touch("movie.7z.002")
        // .003 is missing; .004 is not part of this run.
        touch("movie.7z.004")
        assertEquals(
            listOf("movie.7z.001", "movie.7z.002"),
            SevenZipNative.volumesOf(one).map { File(it).name },
        )
    }

    @Test
    fun `a lone 7z is its own one-volume set`() {
        val single = touch("notes.7z")
        assertEquals(listOf("notes.7z"), SevenZipNative.volumesOf(single).map { File(it).name })
    }

    @Test
    fun `only the first 7z volume opens the set`() {
        touch("show.7z.001")
        val second = touch("show.7z.002")
        // A middle part carries no header of its own, so it opens only itself
        // -- and, having no 7z magic, is not offered as an archive at all.
        assertEquals(listOf("show.7z.002"), SevenZipNative.volumesOf(second).map { File(it).name })
        assertTrue(Archives.looksLikeArchive("show.7z.001"))
        assertFalse(Archives.looksLikeArchive("show.7z.002"))
    }

    @Test
    fun `a rar volume opens from its first part`() {
        val first = touch("movie.part1.rar")
        val third = touch("movie.part3.rar")
        touch("movie.part2.rar")
        assertEquals("movie.part1.rar", RarNative.firstVolume(third).name)
        assertEquals("movie.part1.rar", RarNative.firstVolume(first).name)
    }

    @Test
    fun `a padded rar part number keeps its width`() {
        val first = touch("film.part01.rar")
        val second = touch("film.part02.rar")
        assertEquals("film.part01.rar", RarNative.firstVolume(second).name)
    }

    @Test
    fun `an old-style rar volume opens from the rar`() {
        val rar = touch("clip.rar")
        val r00 = touch("clip.r00")
        touch("clip.r01")
        assertEquals("clip.rar", RarNative.firstVolume(r00).name)
        assertEquals("clip.rar", RarNative.firstVolume(rar).name)
    }

    @Test
    fun `a plain rar is its own first volume`() {
        val rar = touch("solo.rar")
        assertEquals("solo.rar", RarNative.firstVolume(rar).name)
    }

    @Test
    fun `the comic and split extensions are browsable`() {
        assertTrue(Archives.looksLikeArchive("book.cbz"))
        assertTrue(Archives.looksLikeArchive("scan.cbt"))
        assertTrue(Archives.looksLikeArchive("movie.part2.rar"))
    }
}
