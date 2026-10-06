package org.filezilla.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NumberedNameTest {

    /**
     * The bug this fixes, in the exact shape it was reported: the platform
     * numbered "movie.mkv" as "movie.mkv (1)", whose extension is " (1)", so
     * no player would open it.
     */
    @Test
    fun `the number goes before the extension`() {
        assertEquals(
            "One.Night.Only.2026.1080p.10bit.WEBRip.6CH.x265.HEVC-PSA (1).mkv",
            numberedName("One.Night.Only.2026.1080p.10bit.WEBRip.6CH.x265.HEVC-PSA.mkv", 1),
        )
    }

    @Test
    fun `a name with dots keeps every one of them`() {
        assertEquals("a.b.c (2).srt", numberedName("a.b.c.srt", 2))
    }

    @Test
    fun `a name with no extension takes the number at the end`() {
        assertEquals("README (1)", numberedName("README", 1))
    }

    /** A leading dot is a hidden file, not an extension. */
    @Test
    fun `a dotfile is not split at its leading dot`() {
        assertEquals(".gitignore (1)", numberedName(".gitignore", 1))
        assertEquals(".env (3)", numberedName(".env", 3))
    }

    /**
     * Only the last extension moves. "archive.tar (1).gz" is what a file
     * manager produces, and keeps .gz working, which is what matters.
     */
    @Test
    fun `a double extension numbers before the last part`() {
        assertEquals("archive.tar (1).gz", numberedName("archive.tar.gz", 1))
    }

    @Test
    fun `a trailing dot does not lose the name`() {
        assertEquals("odd (1).", numberedName("odd.", 1))
    }

    @Test
    fun `later numbers read the same way`() {
        assertEquals("movie (7).mkv", numberedName("movie.mkv", 7))
        assertEquals("movie (42).mkv", numberedName("movie.mkv", 42))
    }

    /** Korean names go through the same path; nothing here is ASCII-only. */
    @Test
    fun `a korean name numbers the same`() {
        assertEquals("놀라운 토요일 E430 (1).mp4", numberedName("놀라운 토요일 E430.mp4", 1))
    }

    // ---------------------------------------------------- firstFreeName

    /** A name nothing uses is returned as-is, not numbered for no reason. */
    @Test
    fun `a free base name is kept`() {
        assertEquals("movie.mkv", firstFreeName("movie.mkv") { false })
    }

    /** The base taken, the first free number after it is chosen. */
    @Test
    fun `a taken base steps to the first free number`() {
        val used = setOf("movie.mkv", "movie (1).mkv", "movie (2).mkv")
        assertEquals("movie (3).mkv", firstFreeName("movie.mkv") { it in used })
    }

    /**
     * The gap matters: numbering restarts from the lowest free number, not
     * from one past the highest, so "(1)" is reused once "(1)" is gone.
     */
    @Test
    fun `numbering fills the lowest free slot`() {
        val used = setOf("a.txt", "a (2).txt")
        assertEquals("a (1).txt", firstFreeName("a.txt") { it in used })
    }

    /** Past the cap it refuses rather than spinning forever. */
    @Test
    fun `everything taken is refused, not looped`() {
        assertThrows(java.io.IOException::class.java) {
            firstFreeName("x.txt") { true }
        }
    }
}
