package org.filezilla.android.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * That a clipboard still knows which of its names are folders.
 *
 * A clipboard carries names, not rows, and a name on its own does not say
 * whether it is a folder. The paste that turns a clipboard into a transfer
 * looked the rows up in the *active* pane -- which by then is the pane being
 * pasted into, not the one they came from. Nothing matched, every pick fell
 * back to a bare entry whose isDirectory is false, and a folder pasted from a
 * server was planned as a file: the download fetched nothing.
 */
@RunWith(RobolectricTestRunner::class)
class HeldRowsTest {

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

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun open(model: MainViewModel, id: PaneId, dir: File) {
        model.openPath(id, dir.absolutePath)
        waitFor("$dir") { model.pane(id).path == dir.absolutePath && !model.pane(id).loading }
    }

    /**
     * Copied in one pane, and the *other* pane is the one in front by the
     * time the paste happens. The folder must still be known to be a folder.
     */
    @Test
    fun `a folder stays a folder after the panes have moved on`() {
        val from = folder.newFolder("from")
        val to = folder.newFolder("to")
        File(from, "Season").mkdirs()
        File(from, "Season/ep1.mkv").writeText("x")

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, from)
        model.toggleSelected("Season")
        model.copySelection(PaneId.LEFT)

        // The other pane comes to the front and is pasted into, which is what
        // moves activePane off the pane the rows are in.
        model.showPane(PaneId.RIGHT)
        open(model, PaneId.RIGHT, to)
        model.paste(PaneId.RIGHT)
        waitFor("Season to arrive") { File(to, "Season/ep1.mkv").exists() }

        assertTrue("it was copied as a file", File(to, "Season").isDirectory)
    }

    /** And the copy is whole, not just the folder's name. */
    @Test
    fun `what was inside the folder comes with it`() {
        val from = folder.newFolder("from")
        val to = folder.newFolder("to")
        File(from, "Season/extras").mkdirs()
        File(from, "Season/ep1.mkv").writeText("one")
        File(from, "Season/extras/notes.txt").writeText("two")

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, from)
        model.toggleSelected("Season")
        model.copySelection(PaneId.LEFT)
        model.showPane(PaneId.RIGHT)
        open(model, PaneId.RIGHT, to)
        model.paste(PaneId.RIGHT)
        waitFor("the tree to arrive") { File(to, "Season/extras/notes.txt").exists() }

        assertEquals("one", File(to, "Season/ep1.mkv").readText())
        assertEquals("two", File(to, "Season/extras/notes.txt").readText())
    }
}
