package org.filezilla.android.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.filezilla.ftp.listing.DirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Copy, cut, paste and rename on the phone's own side, driven through the
 * view model the way the screen drives it.
 *
 * The pure pieces underneath -- [PasteRules], [org.filezilla.android.files.LocalOperations] --
 * are tested on their own and pass. What was never tested is the wiring
 * between them, which is where the user's "복사도 안되네" lives: every part
 * works and the sequence does not.
 */
@RunWith(RobolectricTestRunner::class)
class LocalActionsTest {

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

    /**
     * Pumps the main looper until [condition] holds.
     *
     * The view model launches on the main dispatcher and does its file work
     * on IO, so neither a plain call nor a single idle() is enough: the test
     * has to let both sides run.
     */
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
        waitFor("$dir to be listed") { model.pane(id).path == dir.absolutePath && !model.pane(id).loading }
    }

    private fun entry(model: MainViewModel, id: PaneId, name: String): DirectoryEntry =
        model.pane(id).entries.first { it.name == name }

    // --------------------------------------------------------- copy, paste

    @Test
    fun `a copied file is pasted into another folder`() {
        val from = folder.newFolder("from")
        val to = folder.newFolder("to")
        File(from, "a.txt").writeText("hello")

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, from)

        model.toggleSelected("a.txt")
        model.copySelection(PaneId.LEFT)
        assertNotNull("nothing was picked up", model.clipboard)

        open(model, PaneId.LEFT, to)
        assertNull("the paste was refused", model.pasteRefusal(PaneId.LEFT))

        model.paste(PaneId.LEFT)
        waitFor("a.txt to arrive") { File(to, "a.txt").exists() }

        assertEquals("hello", File(to, "a.txt").readText())
        assertTrue("the original was taken", File(from, "a.txt").exists())
    }

    @Test
    fun `a cut file is moved rather than copied`() {
        val from = folder.newFolder("from")
        val to = folder.newFolder("to")
        File(from, "a.txt").writeText("hello")

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, from)

        model.toggleSelected("a.txt")
        model.cutSelection(PaneId.LEFT)

        open(model, PaneId.LEFT, to)
        model.paste(PaneId.LEFT)
        waitFor("a.txt to arrive") { File(to, "a.txt").exists() }

        assertTrue("the original is still there", !File(from, "a.txt").exists())
    }

    @Test
    fun `a copied folder is pasted with what is in it`() {
        val from = folder.newFolder("from")
        val to = folder.newFolder("to")
        File(from, "Photos").mkdirs()
        File(from, "Photos/one.jpg").writeText("x")

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, from)

        model.toggleSelected("Photos")
        model.copySelection(PaneId.LEFT)
        open(model, PaneId.LEFT, to)
        model.paste(PaneId.LEFT)
        waitFor("Photos to arrive") { File(to, "Photos/one.jpg").exists() }
    }

    // ------------------------------------------------------------- rename

    @Test
    fun `a local folder is renamed`() {
        val here = folder.newFolder("here")
        File(here, "Before").mkdirs()

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, here)

        model.rename(entry(model, PaneId.LEFT, "Before"), "After")
        waitFor("the folder to be renamed") { File(here, "After").isDirectory }

        assertTrue("the old name is still there", !File(here, "Before").exists())
    }

    @Test
    fun `a local file is renamed`() {
        val here = folder.newFolder("here")
        File(here, "before.txt").writeText("x")

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, here)

        model.rename(entry(model, PaneId.LEFT, "before.txt"), "after.txt")
        waitFor("the file to be renamed") { File(here, "after.txt").exists() }
    }

    /** A rename that cannot work has to say so, not fail quietly. */
    @Test
    fun `a rename onto a name already taken is reported`() {
        val here = folder.newFolder("here")
        File(here, "one").mkdirs()
        File(here, "two").mkdirs()

        val model = MainViewModel(application)
        model.showPane(PaneId.LEFT)
        open(model, PaneId.LEFT, here)

        model.rename(entry(model, PaneId.LEFT, "one"), "two")
        waitFor("the failure to be shown") { model.pane(PaneId.LEFT).error != null }
    }
}
