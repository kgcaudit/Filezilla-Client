package org.filezilla.android.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A comic keeping its place, and not keeping every comic's place for ever.
 *
 * Reopening a book where it was left is the whole point; the cap is the other
 * half of it, so a reader who opens thousands of comics over a phone's life
 * does not grow a preferences file that never shrinks.
 */
@RunWith(RobolectricTestRunner::class)
class ComicProgressTest {

    private lateinit var preferences: AppPreferences

    @Before
    fun setUp() {
        preferences = AppPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `a comic not opened yet has no page`() {
        assertNull(preferences.comicPage("arc:/Download/manga.cbz"))
    }

    @Test
    fun `the page a comic was left on comes back`() {
        preferences.setComicPage("arc:/Download/manga.cbz", 42)
        assertEquals(42, preferences.comicPage("arc:/Download/manga.cbz"))
    }

    @Test
    fun `a later page replaces the earlier one`() {
        preferences.setComicPage("arc:/x.cbz", 3)
        preferences.setComicPage("arc:/x.cbz", 7)
        assertEquals(7, preferences.comicPage("arc:/x.cbz"))
    }

    @Test
    fun `the oldest comic's place is forgotten once the cap is passed`() {
        val cap = AppPreferences.MAX_REMEMBERED_COMICS
        // The first book, then enough others to push it out.
        preferences.setComicPage("first", 10)
        for (i in 0 until cap) preferences.setComicPage("book$i", i)

        assertNull("the oldest should have been forgotten", preferences.comicPage("first"))
        assertEquals("the newest should be kept", cap - 1, preferences.comicPage("book${cap - 1}"))
    }

    @Test
    fun `reading direction defaults left-to-right and persists once set`() {
        assertFalse(preferences.readerRtl)
        preferences.readerRtl = true
        assertTrue(preferences.readerRtl)
    }
}
