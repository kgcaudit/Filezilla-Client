package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseSelectionTest {

    private val idle = BrowseState(path = "/HDD1")

    /**
     * Tapping a row's icon is a way into selection mode. If picking did not
     * turn the mode on, the first tap would add to a selection the screen is
     * not showing, and would look like nothing had happened.
     */
    @Test
    fun `picking a row turns selection mode on`() {
        assertFalse(idle.selecting)

        val picked = idle.withToggled("Vision")

        assertTrue(picked.selecting)
        assertEquals(setOf("Vision"), picked.selection)
    }

    @Test
    fun `picking again unpicks`() {
        val picked = idle.withToggled("Vision").withToggled("Vision")

        assertEquals(emptySet<String>(), picked.selection)
    }

    @Test
    fun `rows accumulate`() {
        val picked = idle.withToggled("HDD1").withToggled("HDD2")

        assertEquals(setOf("HDD1", "HDD2"), picked.selection)
    }

    /**
     * Unpicking the last row leaves the mode on: the user is mid-selection and
     * dropping them out of it would take the toolbar away between two taps.
     */
    @Test
    fun `unpicking the last row stays in selection mode`() {
        val empty = idle.withToggled("Vision").withToggled("Vision")

        assertTrue(empty.selecting)
        assertTrue(empty.selection.isEmpty())
    }

    @Test
    fun `nothing else about the screen changes`() {
        val before = idle.copy(path = "/HDD1", filter = "mp4")

        val after = before.withToggled("Vision")

        assertEquals(before.path, after.path)
        assertEquals(before.filter, after.filter)
        assertEquals(before.entries, after.entries)
    }

    /** A pick cannot outlive the row it was made on. */
    @Test
    fun `a selection is pruned to the rows still listed`() {
        val picked = idle.withToggled("Vision").withToggled("Gone")

        val kept = picked.prunedSelection(listOf(DirectoryEntry(name = "Vision", isDirectory = true)))

        assertEquals(setOf("Vision"), kept)
    }
}
