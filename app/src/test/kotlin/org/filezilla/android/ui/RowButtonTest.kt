package org.filezilla.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows carry a button of their own.
 *
 * The user asked what the arrow at the end of a file row on the phone was
 * for -- whether it marked the files that had an app to open them. It never
 * meant that. It called `onDownload`, which is exactly what tapping the row
 * calls: a second control for the same act, sitting inside the control that
 * already did it, on both sides of the app.
 *
 * It earns its place on a server and nowhere else. "Tap a file to download
 * it" is not something a listing teaches by itself, and a file's own menu
 * there offers no download. On the phone, tapping a file to open it is what
 * tapping a file means everywhere.
 */
class RowButtonTest {

    /** The one row that keeps it. */
    @Test
    fun `a file on a server offers to be fetched`() {
        assertTrue(showsFetchButton(isLocal = false, isDirectory = false))
    }

    /** Tapping it opens it, and a second button for that says nothing. */
    @Test
    fun `a file on the phone carries no button of its own`() {
        assertFalse(showsFetchButton(isLocal = true, isDirectory = false))
    }

    /**
     * A folder is walked into, not fetched. Asking for one on a server is in
     * its menu, where it has room to say "download this folder".
     */
    @Test
    fun `a folder carries no button on either side`() {
        assertFalse(showsFetchButton(isLocal = false, isDirectory = true))
        assertFalse(showsFetchButton(isLocal = true, isDirectory = true))
    }
}
