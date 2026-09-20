package org.filezilla.android.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * That a pane never shows one folder's rows under another folder's name.
 *
 * A listing is asked for on the main thread and answered from IO, and the
 * pane's path is set the moment it is asked for -- so two listings can be in
 * flight at once, and the one that lands last wins regardless of which was
 * asked for last. Tapping into a folder while the previous one was still
 * being read left the header naming the new folder and the rows belonging to
 * the old one, or to nothing at all.
 *
 * It showed up first as a test that failed about one run in three, which is
 * the same bug wearing a different hat: [LocalActionsTest] opens a folder,
 * waits for the pane to settle, and then cannot find the file it just wrote,
 * because what settled the pane was a listing of somewhere else.
 *
 * Racy by nature, so it is run enough times to be sure rather than once.
 */
@RunWith(RobolectricTestRunner::class)
class StaleListingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetRememberedPanes() {
        val prefs = org.filezilla.android.AppGraph.of(application).preferences
        for (id in PaneId.entries) {
            prefs.setPaneIsLocal(id.name, true)
            prefs.setPaneSiteId(id.name, null)
            prefs.setPanePath(id.name, "local", null)
        }
    }

    @Test
    fun `the rows a pane settles on are the rows of the folder it is showing`() {
        // Two folders whose contents name them, so rows from the wrong one
        // are not merely a different count but plainly the other folder's.
        val first = folder.newFolder("first").also { File(it, "in-first").writeText("x") }
        val second = folder.newFolder("second").also { File(it, "in-second").writeText("x") }

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)

        repeat(40) { round ->
            val (from, to) = if (round % 2 == 0) first to second else second to first

            // Both asked for in the same turn of the main looper, which is
            // what a second tap before the first listing lands looks like.
            model.openPath(PaneId.LEFT, from.absolutePath)
            model.openPath(PaneId.LEFT, to.absolutePath)

            settle(model)

            val state = model.pane(PaneId.LEFT)
            assertEquals("round $round: the pane is not where it was sent", to.absolutePath, state.path)
            assertEquals(
                "round $round: showing ${from.name}'s rows under ${to.name}",
                listOf("in-${to.name}"),
                state.entries.map { it.name },
            )
        }
    }

    /** Pumps until the pane stops loading and both listings have had their say. */
    private fun settle(model: MainViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!model.pane(PaneId.LEFT).loading) {
                // A late answer from the first listing would arrive after
                // this point, so give it the chance rather than racing past.
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
                return
            }
            Thread.sleep(5)
        }
        throw AssertionError("the pane never stopped loading")
    }
}
