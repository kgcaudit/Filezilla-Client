package org.filezilla.android.endtoend

import org.filezilla.android.AppGraph
import org.filezilla.android.ui.PaneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tapping a file on a server opens it, end to end.
 *
 * Before this, a tap and the download button beside it called the same
 * function -- so looking at a text file on a server began by choosing a
 * folder to keep it in, and if no folder was chosen it was refused
 * outright. The button still keeps a copy. The tap fetches one and opens
 * it.
 */
@RunWith(RobolectricTestRunner::class)
class OpenFromServerTest : AppAgainstAServer() {

    private fun graph() = AppGraph.of(application)

    private fun tap(model: org.filezilla.android.ui.MainViewModel, name: String) {
        val row = model.pane(PaneId.LEFT).entries.first { it.name == name }
        model.viewOnServer(PaneId.LEFT, row)
    }

    @Test
    fun `a file is fetched and handed over to be opened`() {
        File(onServer, "notes.txt").writeText("hello from the server")

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "notes.txt" } }

        tap(model, "notes.txt")
        waitFor("the copy") { model.readyToOpen != null }

        val copy = model.readyToOpen!!
        assertEquals("hello from the server", copy.readText())
        // Keeping the extension is not cosmetic: it is what tells the app
        // that opens it what kind of file this is.
        assertEquals("txt", copy.extension)
        assertNull("nothing should still be in flight", model.viewing)
        assertNull(model.pane(PaneId.LEFT).error)
    }

    @Test
    fun `no download folder is needed to look at a file`() {
        File(onServer, "notes.txt").writeText("x")

        val model = model()
        // Both panes on the server, so there is nowhere to download to --
        // which used to be enough to refuse the tap.
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "notes.txt" } }

        tap(model, "notes.txt")
        waitFor("the copy") { model.readyToOpen != null }

        assertEquals("x", model.readyToOpen!!.readText())
    }

    @Test
    fun `the same file a second time does not go back to the server`() {
        File(onServer, "notes.txt").writeText("hello")

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "notes.txt" } }
        tap(model, "notes.txt")
        waitFor("the copy") { model.readyToOpen != null }
        model.openedReady()

        val spent = graph().log.log.value.size
        tap(model, "notes.txt")
        waitFor("the held copy") { model.readyToOpen != null }

        assertEquals(
            "a copy already held should open without asking the server again",
            spent,
            graph().log.log.value.size,
        )
    }

    @Test
    fun `a file changed on the server is fetched again`() {
        val file = File(onServer, "notes.txt")
        file.writeText("first")

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "notes.txt" } }
        tap(model, "notes.txt")
        waitFor("the copy") { model.readyToOpen != null }
        model.openedReady()

        // The same length on purpose. A different length is caught by the
        // size check on its own, so a test that grew the file would pass
        // whether or not the modification time was part of the key -- and
        // an edit that keeps the length is the ordinary case: a date
        // corrected, a flag flipped, a typo fixed.
        file.writeText("secnd")
        file.setLastModified(file.lastModified() + 120_000)

        model.open(PaneId.LEFT)
        waitFor("the new listing") { !model.pane(PaneId.LEFT).loading }
        tap(model, "notes.txt")
        waitFor("the new copy") { model.readyToOpen != null }

        assertEquals(
            "the copy from before the edit was opened as though it were what " +
                "the server holds",
            "secnd",
            model.readyToOpen!!.readText(),
        )
    }

    @Test
    fun `stopping a fetch leaves nothing half-written behind`() {
        // Big enough that it is still going when it is stopped.
        server.putFile("film.bin", 8 * 1024 * 1024)

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "film.bin" } }

        tap(model, "film.bin")
        waitFor("the fetch to start") { model.viewing != null }
        model.cancelViewing()
        waitFor("the fetch to stop") { model.viewing == null }

        assertNull("a stopped fetch must not open anything", model.readyToOpen)
        // Half a file left in the cache would be found by the next tap and
        // opened as though it were whole.
        assertEquals(0, graph().viewCache.totalBytes())
        assertNull("stopping is not a failure to report", model.viewingFailure)
    }

    @Test
    fun `the read-only notice is shown once and then not again`() {
        File(onServer, "notes.txt").writeText("x")
        File(onServer, "other.txt").writeText("y")

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the rows") { model.pane(PaneId.LEFT).entries.size >= 2 }

        tap(model, "notes.txt")
        waitFor("the copy") { model.readyToOpen != null }
        assertTrue("the first open should say what opening means", model.warnReadOnly)

        model.acknowledgeReadOnly()
        model.openedReady()

        tap(model, "other.txt")
        waitFor("the second copy") { model.readyToOpen != null }

        // Said every time, it would be dismissed without reading, which is
        // the same as not saying it.
        assertEquals(false, model.warnReadOnly)
    }

    @Test
    fun `a file that is not there says so rather than opening nothing`() {
        File(onServer, "gone.txt").writeText("x")

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "gone.txt" } }

        File(onServer, "gone.txt").delete()
        tap(model, "gone.txt")
        waitFor("the failure") { model.viewingFailure != null }

        assertNotNull(model.viewingFailure)
        assertNull(model.readyToOpen)
        assertEquals(0, graph().viewCache.totalBytes())
    }

    @Test
    fun `a folder is not something to fetch`() {
        File(onServer, "folder").mkdirs()

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "folder" } }

        tap(model, "folder")

        assertNull(model.viewing)
        assertNull(model.readyToOpen)
    }
}
