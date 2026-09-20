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
 * Searching below a folder on a real server, through the app.
 *
 * The walk itself is covered by DeepSearchTest against a tree made of maps.
 * What that cannot cover is the join: one session held open for the whole
 * walk, `CWD` before each `LIST`, paths built the way the server expects,
 * and results reaching the screen while it is still going. Every bug the
 * user has found in the last several rounds lived at a join like this one.
 */
@RunWith(RobolectricTestRunner::class)
class SearchOnServerTest : AppAgainstAServer() {

    private fun tree() {
        File(onServer, "MOVIE/라이브").mkdirs()
        File(onServer, "MUSIC/2025").mkdirs()
        File(onServer, "MUSIC/2025/나토리 - Iris out.mp3").writeText("one")
        File(onServer, "MOVIE/라이브/킨모쿠세이 - 나토리.mkv").writeText("two")
        File(onServer, "MOVIE/그 밖의 것.mkv").writeText("three")
    }

    @Test
    fun `it finds what is buried in subfolders`() {
        tree()
        val site = savedSite()
        val model = model()
        openOnServer(model, PaneId.LEFT, site)

        model.toggleFilter()
        model.setFilter("나토리")
        // The filter alone sees only this folder, which is the whole reason
        // the deeper search exists: two folders here and neither is named
        // for what is being looked for.
        assertEquals(emptyList<String>(), model.visibleEntries(PaneId.LEFT).map { it.name })

        model.searchDeeper(PaneId.LEFT)
        waitFor("the search to finish") { model.pane(PaneId.LEFT).search?.running == false }

        val found = model.pane(PaneId.LEFT).search!!
        assertEquals(
            listOf("/MOVIE/라이브/킨모쿠세이 - 나토리.mkv", "/MUSIC/2025/나토리 - Iris out.mp3"),
            found.hits.map { it.path }.sorted(),
        )
        assertFalse("claimed to have stopped early", found.truncated)
        // Every folder it had to open to say that, which is what the cost
        // of a deep search actually is: the root, MOVIE and MUSIC, and the
        // one folder under each of them.
        assertEquals(5, found.foldersRead)
    }

    /** Where each one lives, which is what makes a result usable. */
    @Test
    fun `a result carries the folder it was found in`() {
        tree()
        val site = savedSite()
        val model = model()
        openOnServer(model, PaneId.LEFT, site)

        model.setFilter("Iris")
        model.searchDeeper(PaneId.LEFT)
        waitFor("the search to finish") { model.pane(PaneId.LEFT).search?.running == false }

        val hit = model.pane(PaneId.LEFT).search!!.hits.single()
        assertEquals("/MUSIC/2025", hit.folder)
    }

    /** Tapping one goes to its folder, and the pane really opens there. */
    @Test
    fun `opening a result lands in the folder holding it`() {
        tree()
        val site = savedSite()
        val model = model()
        openOnServer(model, PaneId.LEFT, site)

        model.setFilter("Iris")
        model.searchDeeper(PaneId.LEFT)
        waitFor("the search to finish") { model.pane(PaneId.LEFT).search?.running == false }

        model.openHit(PaneId.LEFT, model.pane(PaneId.LEFT).search!!.hits.single())
        waitFor("the folder to open") { model.pane(PaneId.LEFT).path == "/MUSIC/2025" }

        assertTrue(
            "the file is not in the folder it said",
            model.pane(PaneId.LEFT).entries.any { it.name == "나토리 - Iris out.mp3" },
        )
        // The results are put away: they answered a question about somewhere
        // else, and leaving them up over a folder the user has now opened
        // would be two listings claiming the same screen.
        assertEquals(null, model.pane(PaneId.LEFT).search)
    }

    /** Typing on changes the question, so the old answer goes. */
    @Test
    fun `changing the filter puts the old results away`() {
        tree()
        val site = savedSite()
        val model = model()
        openOnServer(model, PaneId.LEFT, site)

        model.setFilter("나토리")
        model.searchDeeper(PaneId.LEFT)
        waitFor("the search to finish") { model.pane(PaneId.LEFT).search?.running == false }
        assertTrue(model.pane(PaneId.LEFT).search!!.hits.isNotEmpty())

        model.setFilter("나토리와")

        assertEquals(null, model.pane(PaneId.LEFT).search)
    }

    @Test
    fun `a name nothing matches says so rather than showing an empty folder`() {
        tree()
        val site = savedSite()
        val model = model()
        openOnServer(model, PaneId.LEFT, site)

        model.setFilter("존재하지않는이름")
        model.searchDeeper(PaneId.LEFT)
        waitFor("the search to finish") { model.pane(PaneId.LEFT).search?.running == false }

        val found = model.pane(PaneId.LEFT).search!!
        assertEquals(emptyList<String>(), found.hits.map { it.path })
        assertFalse(found.truncated)
        assertTrue("it did not actually look", found.foldersRead > 1)
    }
}
