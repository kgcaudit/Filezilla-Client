package org.filezilla.android.endtoend

import org.filezilla.android.AppGraph
import org.filezilla.android.ui.PaneId
import org.filezilla.ftp.protocol.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What walking back up a tree costs, and what it must still be told.
 *
 * Going into a folder and back out re-asked the server for a listing it
 * had given a second earlier. Now it does not -- and the whole question
 * worth testing is whether the times it still has to ask are covered,
 * because a cache that is fast and wrong is worse than the round trips it
 * saved.
 */
@RunWith(RobolectricTestRunner::class)
class CachedBrowsingTest : AppAgainstAServer() {

    private fun commands(): Int = AppGraph.of(application).log.log.value
        .count { it.level == LogLevel.COMMAND }

    private fun namesIn(model: org.filezilla.android.ui.MainViewModel) =
        model.pane(PaneId.LEFT).entries.map { it.name }.sorted()

    @Test
    fun `walking back up asks the server nothing`() {
        File(onServer, "a/b").mkdirs()
        File(onServer, "a/one.txt").writeText("x")

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())

        model.openChild(PaneId.LEFT, "a")
        waitFor("a") { model.pane(PaneId.LEFT).path == "/a" }
        model.openChild(PaneId.LEFT, "b")
        waitFor("b") { model.pane(PaneId.LEFT).path == "/a/b" }

        val spent = commands()
        model.up(PaneId.LEFT)
        waitFor("back in a") { model.pane(PaneId.LEFT).path == "/a" }

        assertEquals(
            "walking back into a folder listed a moment ago should cost nothing",
            spent,
            commands(),
        )
        assertEquals(listOf("b", "one.txt"), namesIn(model))
    }

    @Test
    fun `refresh always asks`() {
        File(onServer, "a").mkdirs()

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        model.openChild(PaneId.LEFT, "a")
        waitFor("a") { model.pane(PaneId.LEFT).path == "/a" }

        // Put a file there behind the app's back -- which is exactly what
        // another machine does, and the only thing the cache cannot know
        // about.
        File(onServer, "a/from-elsewhere.txt").writeText("x")

        val spent = commands()
        model.open(PaneId.LEFT)
        waitFor("the refresh") { namesIn(model).contains("from-elsewhere.txt") }

        assertTrue("refresh answered from memory", commands() > spent)
    }

    @Test
    fun `a folder made here is not hidden by what was held`() {
        File(onServer, "a").mkdirs()

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        model.openChild(PaneId.LEFT, "a")
        waitFor("a") { model.pane(PaneId.LEFT).path == "/a" }

        // Root is now held, and says a is the only thing in it.
        model.up(PaneId.LEFT)
        waitFor("root") { model.pane(PaneId.LEFT).path == "/" }
        model.createDirectory("made")
        waitFor("the new folder") { namesIn(model).contains("made") }

        // Away and back. Nothing the pane does here asks the server about
        // root again unless the write threw the held listing away.
        model.openChild(PaneId.LEFT, "a")
        waitFor("a again") { model.pane(PaneId.LEFT).path == "/a" }
        model.up(PaneId.LEFT)
        waitFor("root again") { model.pane(PaneId.LEFT).path == "/" }

        assertEquals(
            "a folder made through the app went missing on the way back to it",
            listOf("a", "made"),
            namesIn(model),
        )
    }

    @Test
    fun `a rename in one folder is not undone by another folder's listing`() {
        File(onServer, "a").mkdirs()
        File(onServer, "a/before.txt").writeText("x")
        File(onServer, "b").mkdirs()

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        model.openChild(PaneId.LEFT, "a")
        waitFor("a") { model.pane(PaneId.LEFT).path == "/a" }
        model.rename(
            org.filezilla.ftp.listing.DirectoryEntry(name = "before.txt"),
            "after.txt",
        )
        waitFor("the rename") { namesIn(model).contains("after.txt") }

        model.up(PaneId.LEFT)
        waitFor("root") { model.pane(PaneId.LEFT).path == "/" }
        model.openChild(PaneId.LEFT, "a")
        waitFor("a again") { model.pane(PaneId.LEFT).path == "/a" }

        assertEquals(listOf("after.txt"), namesIn(model))
    }

    @Test
    fun `an upload drops what was held about the folder it landed in`() {
        val folder = phone.newFolder("outbox")
        File(folder, "sent.txt").writeText("x")
        File(onServer, "drop").mkdirs()

        val site = savedSite()
        val model = model()
        val listings = AppGraph.of(application).transfers.listings

        openOnServer(model, PaneId.LEFT, site)
        model.openChild(PaneId.LEFT, "drop")
        waitFor("drop") { model.pane(PaneId.LEFT).path == "/drop" }

        openOnPhone(model, PaneId.RIGHT, folder)
        model.toggleSelected("sent.txt")
        model.copySelection(PaneId.RIGHT)
        model.openChild(PaneId.LEFT, "drop")
        waitFor("drop again") { model.pane(PaneId.LEFT).path == "/drop" }
        model.pasteAcross(PaneId.LEFT) { }
        waitFor("the upload to be queued") { model.transfers.value.isNotEmpty() }

        // Held, and empty, and about to stop being true. Asserted so that
        // what follows cannot pass by the cache having been empty anyway.
        assertEquals(
            "nothing was being held about the destination, so this proves nothing",
            emptyList<String>(),
            listings.recall(site, "/drop")?.entries?.map { it.name },
        )

        runTheQueue()

        // Read before the looper is pumped, because the app posts a refresh
        // of the destination once a transfer lands and that refresh would
        // fill the cache back in with the right answer -- hiding whether
        // the upload itself had dropped it. The case that matters is the
        // one where the refresh is not the rescue: an upload finishing
        // while the user is looking somewhere else entirely.
        assertNull(
            "the queue has its own connections, so nothing the browse pool " +
                "counts sees an upload; it has to say so itself",
            listings.recall(site, "/drop"),
        )
        assertTrue("the file is on the server", File(onServer, "drop/sent.txt").isFile)
    }
}
