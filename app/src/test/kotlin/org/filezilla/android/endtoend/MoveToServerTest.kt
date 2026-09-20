package org.filezilla.android.endtoend

import org.filezilla.android.ui.PaneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Cutting from the phone and pasting on a server, against a real server.
 *
 * The bug the user reported: "폴더, 파일을 잘라내어 서버에 붙일 경우, 내
 * 저장소에 폴더, 파일이 그대로 남아있어" -- a cut behaved exactly like a
 * copy. Between two devices the paste queues transfers, and nothing after
 * that knew the clipboard had been cut, so the originals stayed where they
 * were. The comment in queueUploads even said the deletion was deliberately
 * left until the transfer had finished; nothing did it then either.
 *
 * It could not have been caught below this level. Each piece was right on
 * its own -- the clipboard knew it was a move, the queue carried the files,
 * the deletion worked when asked -- and what was missing was anything
 * joining them up.
 *
 * The other half is the timing, and it is the half that can cost a file:
 * nothing may be removed before it has arrived. `a file that never arrives
 * is not moved` is that assertion, and it is the one to keep if any of these
 * ever have to go.
 */
@RunWith(RobolectricTestRunner::class)
class MoveToServerTest : AppAgainstAServer() {

    private fun pasteInto(model: org.filezilla.android.ui.MainViewModel, id: PaneId) {
        model.pasteAcross(id) { }
    }

    @Test
    fun `a cut file is gone from the phone once the server has it`() {
        val from = phone.newFolder("from")
        File(from, "film.mkv").writeText("hello")

        val site = savedSite()
        val model = model()
        openOnPhone(model, PaneId.LEFT, from)
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "film.mkv" } }

        model.toggleSelected("film.mkv")
        model.cutSelection(PaneId.LEFT)

        openOnServer(model, PaneId.RIGHT, site)
        pasteInto(model, PaneId.RIGHT)
        waitFor("the queue") { model.transfers.value.isNotEmpty() }
        runTheQueue()

        assertEquals("it did not arrive", "hello", File(onServer, "film.mkv").readText())
        assertFalse("still on the phone", File(from, "film.mkv").exists())
    }

    /** The whole shape of it, which is what the user was looking at. */
    @Test
    fun `a cut folder tree leaves nothing behind on the phone`() {
        val from = phone.newFolder("from")
        val vision = File(from, "Vision").apply { mkdirs() }
        File(vision, "test").mkdirs()
        File(vision, "test/film.mkv").writeText("one")
        File(vision, "subtitle.srt").writeText("two")
        // An empty one, which has no transfer to be carried by and so is the
        // case nothing would ever have removed.
        File(vision, "empty").mkdirs()

        val site = savedSite()
        val model = model()
        openOnPhone(model, PaneId.LEFT, from)
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "Vision" } }

        model.toggleSelected("Vision")
        model.cutSelection(PaneId.LEFT)

        openOnServer(model, PaneId.RIGHT, site)
        pasteInto(model, PaneId.RIGHT)
        waitFor("the queue to hold both files") { model.transfers.value.size >= 2 }
        runTheQueue()

        assertEquals("one", File(onServer, "Vision/test/film.mkv").readText())
        assertEquals("two", File(onServer, "Vision/subtitle.srt").readText())
        assertTrue("the empty folder never arrived", File(onServer, "Vision/empty").isDirectory)

        assertFalse("the file stayed", File(vision, "test/film.mkv").exists())
        assertFalse("the subfolder stayed", File(vision, "test").exists())
        assertFalse("the empty folder stayed", File(vision, "empty").exists())
        assertFalse("the folder itself stayed", vision.exists())
        // And nothing above it: the sweep is licensed for what was cut and
        // nothing else.
        assertTrue("it swept up out of the selection", from.isDirectory)
    }

    /**
     * The assertion that stops this costing somebody a file.
     *
     * The server is stopped before the queue runs, so every upload fails.
     * Nothing may be removed: a move whose transfer did not happen has not
     * moved anything, and the obvious implementation -- delete as the queue
     * is built -- would have thrown the file away here.
     */
    @Test
    fun `a file that never arrives is not moved`() {
        val from = phone.newFolder("from")
        File(from, "film.mkv").writeText("hello")
        File(from, "folder").mkdirs()

        val site = savedSite()
        val model = model()
        openOnPhone(model, PaneId.LEFT, from)
        waitFor("both rows") { model.pane(PaneId.LEFT).entries.size == 2 }

        model.toggleSelected("film.mkv")
        model.toggleSelected("folder")
        model.cutSelection(PaneId.LEFT)

        openOnServer(model, PaneId.RIGHT, site)
        pasteInto(model, PaneId.RIGHT)
        waitFor("the queue") { model.transfers.value.isNotEmpty() }

        server.stop()
        runTheQueue()

        assertTrue("the file was thrown away", File(from, "film.mkv").isFile)
        assertEquals("hello", File(from, "film.mkv").readText())
    }
}
