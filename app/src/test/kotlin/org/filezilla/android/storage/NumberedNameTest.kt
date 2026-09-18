package org.filezilla.android.storage

import org.junit.Assert.assertEquals
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
}
