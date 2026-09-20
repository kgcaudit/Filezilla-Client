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
 * The same move the other way round: cut on the server, paste on the phone.
 *
 * Written because the reported bug was one direction of a symmetrical one.
 * A download queued from a cut left the server's copy exactly where it was,
 * for the same reason the upload did -- the clipboard knew it was a move and
 * nothing past the queue did.
 *
 * The server's half is removed with `DELE` on the connection that fetched
 * the file, and the folders with `RMD`, which FTP refuses for anything not
 * empty. That refusal is the same rule the phone's side applies by hand, and
 * here it comes free.
 */
@RunWith(RobolectricTestRunner::class)
class MoveFromServerTest : AppAgainstAServer() {

    private fun pasteInto(model: org.filezilla.android.ui.MainViewModel, id: PaneId) {
        model.pasteAcross(id) { }
    }

    @Test
    fun `a cut file is gone from the server once the phone has it`() {
        File(onServer, "film.mkv").writeText("hello")
        val to = phone.newFolder("to")

        val site = savedSite()
        val model = model()
        openOnServer(model, PaneId.LEFT, site)
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "film.mkv" } }

        model.toggleSelected("film.mkv")
        model.cutSelection(PaneId.LEFT)

        openOnPhone(model, PaneId.RIGHT, to)
        pasteInto(model, PaneId.RIGHT)
        waitFor("the queue") { model.transfers.value.isNotEmpty() }
        runTheQueue()

        assertEquals("it did not arrive", "hello", File(to, "film.mkv").readText())
        assertFalse("still on the server", File(onServer, "film.mkv").exists())
    }

    @Test
    fun `a cut folder leaves nothing behind on the server`() {
        File(onServer, "Vision/test").mkdirs()
        File(onServer, "Vision/test/film.mkv").writeText("one")
        File(onServer, "Vision/subtitle.srt").writeText("two")
        val to = phone.newFolder("to")

        val site = savedSite()
        val model = model()
        openOnServer(model, PaneId.LEFT, site)
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "Vision" } }

        model.toggleSelected("Vision")
        model.cutSelection(PaneId.LEFT)

        openOnPhone(model, PaneId.RIGHT, to)
        pasteInto(model, PaneId.RIGHT)
        // Named rather than counted. A count can be reached while the walk
        // is still queueing, and the queue then runs with half the move in
        // it -- which is a test that fails now and then for a reason that
        // has nothing to do with what it is checking.
        waitFor("both files to be queued") {
            model.transfers.value.map { it.remotePath }.containsAll(
                listOf("/Vision/test/film.mkv", "/Vision/subtitle.srt"),
            )
        }
        runTheQueue()

        assertEquals("one", File(to, "Vision/test/film.mkv").readText())
        assertEquals("two", File(to, "Vision/subtitle.srt").readText())

        assertFalse("the file stayed", File(onServer, "Vision/test/film.mkv").exists())
        assertFalse("the subfolder stayed", File(onServer, "Vision/test").exists())
        assertFalse("the folder itself stayed", File(onServer, "Vision").exists())
    }

    // There is no live "and nothing arrives" case here to match the one in
    // MoveToServerTest. A download against a server that has gone away does
    // not fail: it parks and waits to be retried, which is the right
    // behaviour and makes a test of it a test of the timeout. The rule that
    // guards the server's copy -- COMPLETED, and actually delivered into the
    // user's own folder -- is pinned in MovedSourceTest, where every way it
    // can be false is reachable.
}
