package org.filezilla.android.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which moved file, if any, is accepted as a queued upload's source again.
 *
 * The rule is deliberately strict because the cost of a wrong answer is high:
 * uploading a different file that merely shares a name, under that name, with
 * nothing said. So these pin that a single same-name, same-size file is taken
 * and anything ambiguous is refused.
 */
class SourceRematchTest {

    private fun candidate(path: String, name: String, size: Long) = SourceCandidate(path, name, size)

    @Test
    fun `a single same-name same-size file is the match`() {
        val found = SourceRematch.bestMatch(
            "film.srt",
            107_000,
            listOf(candidate("/a/film.srt", "film.srt", 107_000)),
        )
        assertEquals("/a/film.srt", found?.path)
    }

    @Test
    fun `a different name is not a match`() {
        assertNull(
            SourceRematch.bestMatch(
                "film.srt",
                107_000,
                listOf(candidate("/a/other.srt", "other.srt", 107_000)),
            ),
        )
    }

    @Test
    fun `a same name but different size is not a match`() {
        assertNull(
            SourceRematch.bestMatch(
                "film.srt",
                107_000,
                listOf(candidate("/a/film.srt", "film.srt", 999)),
            ),
        )
    }

    @Test
    fun `two files of the same name and size are a tie and refused`() {
        assertNull(
            SourceRematch.bestMatch(
                "film.srt",
                107_000,
                listOf(
                    candidate("/a/film.srt", "film.srt", 107_000),
                    candidate("/b/film.srt", "film.srt", 107_000),
                ),
            ),
        )
    }

    @Test
    fun `size disambiguates between two same-named files`() {
        val found = SourceRematch.bestMatch(
            "film.srt",
            107_000,
            listOf(
                candidate("/a/film.srt", "film.srt", 999),
                candidate("/b/film.srt", "film.srt", 107_000),
            ),
        )
        assertEquals("/b/film.srt", found?.path)
    }

    @Test
    fun `an unknown size matches by name when it is the only one`() {
        val found = SourceRematch.bestMatch(
            "film.srt",
            null,
            listOf(candidate("/a/film.srt", "film.srt", 123)),
        )
        assertEquals("/a/film.srt", found?.path)
    }
}
