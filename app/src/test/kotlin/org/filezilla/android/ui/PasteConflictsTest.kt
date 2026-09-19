package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a paste inside the phone finds already sitting there.
 *
 * The bug this guards: copying a file and pasting it into a folder that
 * already held one of that name asked nothing at all. The write then failed,
 * silently, and the pane re-listed looking exactly as it had -- so the paste
 * appeared simply not to have happened.
 */
class PasteConflictsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun write(dir: File, name: String, bytes: Int) =
        File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `a name already in the target is reported`() {
        val source = folder.newFolder("from")
        val target = folder.newFolder("to")
        write(source, "report.pdf", 400)
        write(target, "report.pdf", 90)

        val conflicts = localPasteConflicts(target.path, source.path, listOf("report.pdf"))

        assertEquals(1, conflicts.size)
        assertEquals("report.pdf", conflicts.single().displayName)
        // Incoming and existing the right way round: getting these backwards
        // shows the user the sizes swapped, and they answer the question the
        // dialog asked rather than the one it meant.
        assertEquals(400L, conflicts.single().remoteSize)
        assertEquals(90L, conflicts.single().localSize)
    }

    @Test
    fun `a name nothing is using is not reported`() {
        val source = folder.newFolder("from")
        val target = folder.newFolder("to")
        write(source, "report.pdf", 400)

        assertTrue(localPasteConflicts(target.path, source.path, listOf("report.pdf")).isEmpty())
    }

    /** A folder collides with a folder; the paste would merge or fail, not pass. */
    @Test
    fun `a folder of the same name is a conflict too`() {
        val source = folder.newFolder("from")
        val target = folder.newFolder("to")
        File(source, "Photos").mkdir()
        File(target, "Photos").mkdir()

        val conflicts = localPasteConflicts(target.path, source.path, listOf("Photos"))

        assertEquals(1, conflicts.size)
        // A directory has no size worth showing; -1 is what the dialog reads
        // as "no size", and a filesystem's own bookkeeping figure shown beside
        // a folder name reads as a claim about what is inside it.
        assertEquals(-1L, conflicts.single().localSize)
        assertEquals(null, conflicts.single().remoteSize)
    }

    @Test
    fun `only the colliding names of a batch are reported`() {
        val source = folder.newFolder("from")
        val target = folder.newFolder("to")
        for (name in listOf("a.txt", "b.txt", "c.txt")) write(source, name, 10)
        write(target, "b.txt", 10)

        val conflicts = localPasteConflicts(
            target.path,
            source.path,
            listOf("a.txt", "b.txt", "c.txt"),
        )

        assertEquals(listOf("b.txt"), conflicts.map { it.displayName })
    }

    // ------------------------------------------------------- keeping both

    @Test
    fun `a free name is left alone`() {
        val target = folder.newFolder("to")

        assertEquals("report.pdf", freeNameIn(target.path, "report.pdf"))
    }

    @Test
    fun `a taken name is numbered before its extension`() {
        val target = folder.newFolder("to")
        write(target, "report.pdf", 1)

        assertEquals("report (1).pdf", freeNameIn(target.path, "report.pdf"))
    }

    /**
     * The case that made counting worth it: pasting a third copy must not
     * land back on the second, which would be the silent overwrite this
     * whole path exists to prevent.
     */
    @Test
    fun `numbering counts past names already taken`() {
        val target = folder.newFolder("to")
        write(target, "report.pdf", 1)
        write(target, "report (1).pdf", 1)

        assertEquals("report (2).pdf", freeNameIn(target.path, "report.pdf"))
    }
}
