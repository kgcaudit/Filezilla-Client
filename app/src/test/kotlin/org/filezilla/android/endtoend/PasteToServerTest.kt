package org.filezilla.android.endtoend

import org.filezilla.android.service.QueueNotice
import org.filezilla.ftp.journal.TransferState
import org.filezilla.android.ui.PaneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Copying from the phone to a server, through the app, to a real server.
 *
 * The bug this is here for reached the user and could not have been caught
 * below this level: an empty folder was not copied at all. The upload was
 * built by walking the selection for files, so a folder holding none
 * produced nothing to queue -- no row, no error, and no folder on the server
 * afterwards. Every piece was fine on its own; what was wrong lived between
 * them, in what the paste thought a copy consisted of.
 *
 * The other upload bug the user hit -- every file refused with
 * `550 ... No such file or directory` because nothing made the folders they
 * were addressed into -- is deliberately *not* covered here, and removing
 * that fix leaves these tests green. The paste now makes every folder up
 * front, so this route never reaches the engine's own safety net. That net
 * still earns its place on the routes the paste does not build (a document
 * from the system picker, a queue resumed after a restart) and has its own
 * live-server test in `UploadIntoNewFoldersTest`. Saying so here because a
 * test that quietly stopped covering what its name suggests is worse than
 * one that never claimed to.
 */
@RunWith(RobolectricTestRunner::class)
class PasteToServerTest : AppAgainstAServer() {

    /**
     * Puts the clipboard down the way the screen does.
     *
     * Not `paste`, which is the phone-to-phone one: between two places the
     * work joins the transfer queue instead, and the screen picks between
     * them by [org.filezilla.android.ui.MainViewModel.pasteKind]. Calling the
     * wrong one is how this test first "passed" while nothing was sent.
     */
    private fun pasteInto(model: org.filezilla.android.ui.MainViewModel, id: PaneId) {
        model.pasteAcross(id) { }
    }

    @Test
    fun `an empty folder is copied along with the files beside it`() {
        val from = phone.newFolder("from")
        File(from, "test").mkdirs()
        File(from, "film.mkv").writeText("hello")

        val site = savedSite()
        val model = model()
        openOnPhone(model, PaneId.LEFT, from)
        waitFor("both rows") { model.pane(PaneId.LEFT).entries.size == 2 }

        model.toggleSelected("test")
        model.toggleSelected("film.mkv")
        model.copySelection(PaneId.LEFT)

        openOnServer(model, PaneId.RIGHT, site)
        pasteInto(model, PaneId.RIGHT)

        // The paste makes the folders itself, so an empty one arrives
        // without waiting for any transfer to run.
        waitFor("the empty folder to arrive") { File(onServer, "test").isDirectory }
        assertTrue(File(onServer, "test").isDirectory)
    }

    /** And on its own, where there is not even a file to ride along with. */
    @Test
    fun `a folder with nothing in it is copied on its own`() {
        val from = phone.newFolder("from")
        File(from, "alone").mkdirs()

        val site = savedSite()
        val model = model()
        openOnPhone(model, PaneId.LEFT, from)
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "alone" } }

        model.toggleSelected("alone")
        model.copySelection(PaneId.LEFT)

        openOnServer(model, PaneId.RIGHT, site)
        pasteInto(model, PaneId.RIGHT)

        waitFor("the folder to arrive") { File(onServer, "alone").isDirectory }
        assertTrue(File(onServer, "alone").isDirectory)
    }

    /**
     * A folder tree, which is the 550 the user's transfer list filled up
     * with: the files were addressed into folders nobody had made.
     */
    @Test
    fun `a folder tree arrives with its files in it`() {
        val from = phone.newFolder("from")
        val vision = File(from, "Vision").apply { mkdirs() }
        File(vision, "test").mkdirs()
        File(vision, "test/film.mkv").writeText("one")
        File(vision, "subtitle.srt").writeText("two")

        val site = savedSite()
        val model = model()
        openOnPhone(model, PaneId.LEFT, from)
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "Vision" } }

        model.toggleSelected("Vision")
        model.copySelection(PaneId.LEFT)

        openOnServer(model, PaneId.RIGHT, site)
        pasteInto(model, PaneId.RIGHT)
        // Queued, then actually carried: a foreground service does this on
        // a phone, and nothing starts one here.
        waitFor("the queue to hold both files") {
            org.filezilla.android.AppGraph.of(application).transfers.let { true } &&
                model.transfers.value.size >= 2
        }
        val outcome = runTheQueue()

        assertTrue("the folder is missing", File(onServer, "Vision").isDirectory)
        assertTrue("the subfolder is missing", File(onServer, "Vision/test").isDirectory)

        // The files themselves, because a queue that never ran would have no
        // failures either -- and a test that only counts failures would pass
        // on an upload that never happened.
        assertEquals("one", File(onServer, "Vision/test/film.mkv").readText())
        assertEquals("two", File(onServer, "Vision/subtitle.srt").readText())

        // And what the run reports of itself, which is what the notice at
        // the end of it is built from. The count is filtered by "since this
        // run started", so a journal carrying older work must not leak in.
        assertEquals(
            org.filezilla.android.transfer.TransferManager.QueueOutcome(completed = 2, failed = 0),
            outcome,
        )
        assertEquals(
            QueueNotice(org.filezilla.android.R.string.done_all, listOf(2)),
            QueueNotice.of(outcome),
        )

        // Nothing was refused. The whole failure was that every file came
        // back 550, so a queue with no failures is the assertion.
        assertEquals(
            emptyList<String>(),
            model.transfers.value.filter { it.state == TransferState.FAILED }
                .map { "${it.remotePath}: ${it.lastError}" },
        )
    }
}
