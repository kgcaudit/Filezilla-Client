package org.filezilla.android.endtoend

import org.filezilla.android.ui.PaneId
import org.filezilla.android.ui.ServerWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What a long delete says for itself, against a real server.
 *
 * Reported from a phone: a few hundred files being removed showed nothing
 * but the loading bar, and the only sign anything was happening was the
 * log. FTP has no bulk delete -- it is one `DELE` per file, each a round
 * trip -- so on a link away from home that is a minute of a screen that
 * reads as a broken app rather than a busy one.
 */
@RunWith(RobolectricTestRunner::class)
class DeleteProgressTest : AppAgainstAServer() {

    private fun treeOf(files: Int): File {
        val folder = File(onServer, "pack")
        File(folder, "inner").mkdirs()
        for (i in 1..files) File(folder, "file$i.svg").writeText("x")
        for (i in 1..files) File(folder, "inner/deep$i.svg").writeText("y")
        return folder
    }

    private fun openPack(model: org.filezilla.android.ui.MainViewModel) {
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the folder") { model.pane(PaneId.LEFT).entries.any { it.name == "pack" } }
    }

    @Test
    fun `both halves of a delete are announced, and it clears when done`() {
        // Big enough that the work cannot be over between two looks at
        // it. How the count climbs is checked in RemoteDeleteTest, where
        // nothing depends on catching a fast server mid-stride.
        treeOf(150)
        val model = model()
        openPack(model)

        val kinds = mutableSetOf<ServerWork.Kind>()
        var sawATotal = false
        var sawAName = false

        model.toggleSelected("pack")
        model.deleteSelectionIn(PaneId.LEFT)
        waitFor("the delete to finish", seconds = 30) {
            model.serverWork?.let { work ->
                kinds += work.kind
                if (work.total != null) sawATotal = true
                if (work.current.isNotEmpty()) sawAName = true
            }
            !model.pane(PaneId.LEFT).loading && model.serverWork == null
        }

        // The walk before the first DELE is the half that said nothing at
        // all. How the count climbs is checked in RemoteDeleteTest, where
        // it does not depend on catching a fast server mid-stride.
        assertTrue("the walk before the first DELE said nothing", ServerWork.Kind.SCANNING in kinds)
        assertTrue("the removing said nothing", ServerWork.Kind.DELETING in kinds)
        assertTrue("the removing never said how much there was", sawATotal)
        assertTrue("nothing said which file was being removed", sawAName)

        assertTrue("it did not actually delete", !File(onServer, "pack").exists())
        assertNull(model.pane(PaneId.LEFT).error)
        assertNull("the work was left on screen", model.serverWork)
    }

    @Test
    fun `a small delete is over before anything is shown`() {
        File(onServer, "one.txt").writeText("x")
        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "one.txt" } }

        model.toggleSelected("one.txt")
        model.deleteSelectionIn(PaneId.LEFT)
        waitFor("the delete") { !File(onServer, "one.txt").exists() }
        waitFor("the pane") { model.serverWork == null && !model.pane(PaneId.LEFT).loading }

        // The dialog waits before showing itself; what matters here is that
        // the work is cleared rather than left up.
        assertNull(model.serverWork)
    }

    @Test
    fun `stopping leaves the rest alone and says how far it got`() {
        // 150 files, 150 more inside, and the two folders above them.
        val steps = 150 + 150 + 2
        treeOf(150)
        val model = model()
        openPack(model)

        model.toggleSelected("pack")
        model.deleteSelectionIn(PaneId.LEFT)
        waitFor("the deleting to start", seconds = 30) {
            model.serverWork?.kind == ServerWork.Kind.DELETING &&
                (model.serverWork?.done ?: 0) > 0
        }
        model.stopServerWork()
        waitFor("it to stop", seconds = 30) { model.serverWork == null }

        val outcome = model.workOutcome
        assertNotNull("stopping must say what it managed", outcome)
        assertTrue("it claimed to have deleted nothing", outcome!!.done > 0)
        assertTrue("it claimed to have deleted everything", outcome.done < outcome.total)
        assertEquals("the total it reported is not the plan it was running", steps, outcome.total)

        // walkTopDown counts the folder itself, so this is every entry the
        // plan had left to remove.
        val left = File(onServer, "pack").walkTopDown().count()
        assertEquals(
            "what it says it removed does not match what is actually gone",
            steps - outcome.done,
            left,
        )
    }

    @Test
    fun `stopping during the walk removes nothing at all`() {
        treeOf(8)
        val model = model()
        openPack(model)

        model.toggleSelected("pack")
        model.deleteSelectionIn(PaneId.LEFT)
        waitFor("the walk to start", seconds = 30) { model.serverWork != null }
        model.stopServerWork()
        waitFor("it to stop", seconds = 30) { model.serverWork == null }

        // Nothing had been removed yet, so nothing should be. A walk that
        // was called off is not a plan to run half of.
        assertTrue(File(onServer, "pack/file1.svg").exists())
        assertTrue(File(onServer, "pack/inner/deep1.svg").exists())
    }
}
