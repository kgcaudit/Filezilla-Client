package org.filezilla.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the filter box tells plain text from a wildcard shape.
 *
 * The two intents share one box, so the line between them is the thing worth
 * pinning: plain text stays the forgiving substring it always was, and a query
 * with a wildcard becomes a whole-name shape -- get that boundary wrong and a
 * folder of photos either stops filtering or starts hiding files the user can
 * see.
 */
class NameMatchTest {

    @Test
    fun `an empty query keeps everything`() {
        assertTrue(NameMatch.matches("anything.txt", ""))
        assertTrue(NameMatch.matches("anything.txt", "   "))
    }

    @Test
    fun `plain text is found anywhere and ignores case`() {
        assertTrue(NameMatch.matches("Q3-REPORT.pdf", "report"))
        assertTrue(NameMatch.matches("report.txt", "REPORT"))
        assertFalse(NameMatch.matches("notes.txt", "report"))
    }

    @Test
    fun `a star matches any run of characters over the whole name`() {
        assertTrue(NameMatch.matches("photo.jpg", "*.jpg"))
        assertTrue(NameMatch.matches("scan.JPG", "*.jpg"))
        assertTrue(NameMatch.matches("report-2026.pdf", "report*"))
        // Whole-name, not substring: a plain "jpgs" folder is a shape miss even
        // though it contains "jpg".
        assertFalse(NameMatch.matches("jpgs", "*.jpg"))
    }

    @Test
    fun `a question mark matches exactly one character`() {
        assertTrue(NameMatch.matches("IMG_0042.png", "IMG_????.png"))
        assertFalse(NameMatch.matches("IMG_42.png", "IMG_????.png"))
    }

    @Test
    fun `the dot in a wildcard query is a real dot, not any character`() {
        assertTrue(NameMatch.matches("a.jpg", "*.jpg"))
        // "axjpg" would match if the dot were treated as "any character".
        assertFalse(NameMatch.matches("axjpg", "*.jpg"))
    }

    @Test
    fun `a bare extension without a wildcard stays a substring match`() {
        // No wildcard: the old forgiving behaviour, so "jpg" still finds both a
        // file and a folder that merely contain the letters.
        assertTrue(NameMatch.matches("photo.jpg", "jpg"))
        assertTrue(NameMatch.matches("jpgs", "jpg"))
    }
}
