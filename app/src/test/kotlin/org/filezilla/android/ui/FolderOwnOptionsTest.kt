package org.filezilla.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the rows are arranged by the settings the dialog is showing.
 *
 * The bug the user found: folders were given settings of their own, the
 * pane header and the options dialog were moved onto them, and the listing
 * was not. So ticking "이 폴더만" and choosing a sort changed what the
 * dialog said and left the rows exactly as they were -- the feature looked
 * implemented and did nothing.
 *
 * The fix is structural rather than a line changed: PaneBody asked for the
 * settings once and used that one answer everywhere, and the parameter
 * that had been the other source is gone. So this reads the source and
 * holds it to that, because the failure is not visible from any value the
 * view model returns -- `visibleEntries` was right the whole time. What
 * was wrong was that the screen was not asking it.
 */
class FolderOwnOptionsTest {

    private val paneBody: String
        get() = File("src/main/kotlin/org/filezilla/android/ui/FilePanes.kt")
            .readText()
            .substringAfter("private fun PaneBody(")

    @Test
    fun `the pane takes its settings from the model, not from above`() {
        val signature = paneBody.substringBefore(") {")

        assertTrue(
            "PaneBody has an options parameter again, which is a second " +
                "source of settings and the one the listing used: $signature",
            "options:" !in signature.replace(" ", ""),
        )
        assertTrue(
            "PaneBody never asks the model what this pane is arranged by",
            "model.optionsFor(id)" in paneBody,
        )
    }

    /**
     * And that the listing is sorted by that answer.
     *
     * Named rather than implied: the header and the dialog were both right
     * while this one call was wrong, so "somewhere in the file" is not
     * enough of an assertion.
     */
    @Test
    fun `the rows are arranged by the pane's own settings`() {
        val arranging = paneBody.substringAfter("BrowseListing.arrange(").substringBefore(")")

        assertTrue(
            "the listing is arranged by something other than the pane's " +
                "settings: arrange($arranging)",
            "options" in arranging,
        )
        assertTrue(
            "the pane's settings are read into `options` before they are used",
            "val options = model.optionsFor(id)" in paneBody,
        )
    }
}
