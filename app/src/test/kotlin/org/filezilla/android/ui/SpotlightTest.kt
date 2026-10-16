package org.filezilla.android.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * That an action which lands a result in the folder on screen flags that
 * result to be scrolled to and flashed, and that ordinary navigation forgets
 * the flag.
 *
 * The flash and the scroll live in the screen; this covers the half the model
 * owns -- which row it names, that the refresh after the action keeps the name,
 * and that walking into another folder drops it so it never relights a
 * same-named row somewhere else. Driven through the view model the way the
 * screen drives it, on the phone's own side where no server is needed.
 */
@RunWith(RobolectricTestRunner::class)
class SpotlightTest {

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
        waitFor("$dir to be listed") {
            model.pane(id).path == dir.absolutePath && !model.pane(id).loading
        }
    }

    @Test
    fun `a new folder is flagged for the spotlight and survives the refresh`() {
        val here = folder.newFolder("here")

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, here)

        model.createFolderIn(PaneId.LEFT, "Fresh")
        waitFor("the folder to appear") {
            model.pane(PaneId.LEFT).entries.any { it.name == "Fresh" }
        }

        // Still named once the fresh listing has replaced the rows: the
        // refresh keeps the flag, which is the whole point of carrying it on
        // the state rather than firing it and losing it.
        assertEquals("Fresh", model.pane(PaneId.LEFT).spotlight)
    }

    @Test
    fun `a pasted file is flagged where it lands`() {
        val from = folder.newFolder("from")
        val to = folder.newFolder("to")
        File(from, "doc.txt").writeText("x")

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, from)

        model.toggleSelected("doc.txt")
        model.copySelection(PaneId.LEFT)
        open(model, PaneId.LEFT, to)
        model.paste(PaneId.LEFT)
        waitFor("doc.txt to arrive") { File(to, "doc.txt").exists() }
        waitFor("the paste to settle") {
            model.pane(PaneId.LEFT).entries.any { it.name == "doc.txt" }
        }

        assertEquals("doc.txt", model.pane(PaneId.LEFT).spotlight)
    }

    @Test
    fun `renaming flags the new name`() {
        val here = folder.newFolder("here")
        File(here, "Before").mkdirs()

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, here)

        model.rename(model.pane(PaneId.LEFT).entries.first { it.name == "Before" }, "After")
        waitFor("the rename to settle") {
            model.pane(PaneId.LEFT).entries.any { it.name == "After" }
        }

        assertEquals("After", model.pane(PaneId.LEFT).spotlight)
    }

    @Test
    fun `walking into another folder forgets the spotlight`() {
        val here = folder.newFolder("here")
        val inner = File(here, "inner").apply { mkdirs() }

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, here)

        model.createFolderIn(PaneId.LEFT, "Fresh")
        waitFor("the folder to appear") {
            model.pane(PaneId.LEFT).spotlight == "Fresh"
        }

        open(model, PaneId.LEFT, inner)

        assertNull("the flag followed us into another folder", model.pane(PaneId.LEFT).spotlight)
    }

    @Test
    fun `the pane can report the spotlight shown`() {
        val here = folder.newFolder("here")

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, here)

        model.createFolderIn(PaneId.LEFT, "Fresh")
        waitFor("the flag to be set") { model.pane(PaneId.LEFT).spotlight == "Fresh" }

        model.spotlit(PaneId.LEFT)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(model.pane(PaneId.LEFT).spotlight)
    }
}
