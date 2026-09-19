package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Expanding what was picked on the phone into the files to send.
 *
 * The mirror of the download planner, and it has the same thing to get right:
 * the shape a folder had has to survive the trip, or sending "Vision" strews
 * its contents across whatever folder it lands in.
 */
class LocalWalkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun walk(vararg parts: String) =
        LocalWalk.filesUnder(File(temp.root, parts.joinToString("/")).absolutePath)

    @Test
    fun `a file picked directly goes in at the top`() {
        File(temp.root, "a.txt").writeText("hello")

        val sent = walk("a.txt").single()

        assertEquals("a.txt", sent.name)
        assertEquals(emptyList<String>(), sent.subPath)
        assertEquals(5L, sent.size)
    }

    /**
     * The picked folder's own name is part of the path, which is what makes
     * sending "Vision" produce a "Vision" on the server rather than its
     * contents loose in the folder it was sent to.
     */
    @Test
    fun `a picked folder keeps its own name`() {
        temp.newFolder("Vision")
        File(temp.root, "Vision/a.mkv").writeText("x")

        val sent = walk("Vision").single()

        assertEquals("a.mkv", sent.name)
        assertEquals(listOf("Vision"), sent.subPath)
    }

    @Test
    fun `nested folders keep their whole shape`() {
        temp.newFolder("Vision", "2026", "clips")
        File(temp.root, "Vision/2026/clips/a.mkv").writeText("x")

        val sent = walk("Vision").single()

        assertEquals(listOf("Vision", "2026", "clips"), sent.subPath)
    }

    @Test
    fun `every file under a folder is found`() {
        temp.newFolder("Vision", "2026")
        File(temp.root, "Vision/a.mkv").writeText("a")
        File(temp.root, "Vision/2026/b.mkv").writeText("b")

        assertEquals(setOf("a.mkv", "b.mkv"), walk("Vision").map { it.name }.toSet())
    }

    @Test
    fun `an empty folder sends nothing rather than failing`() {
        temp.newFolder("Empty")

        assertEquals(emptyList<LocalFileToSend>(), walk("Empty"))
    }

    @Test
    fun `something that is not there sends nothing`() {
        assertEquals(emptyList<LocalFileToSend>(), walk("missing"))
    }
}
