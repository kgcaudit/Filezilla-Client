package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The folders a copy has to recreate, including the ones holding nothing.
 *
 * The bug: copying a folder to a server built the upload out of the files
 * under it, so a folder with no files in it produced no uploads, so nothing
 * was queued and nothing was made. The folder just was not there afterwards,
 * with no error and no row in the queue to ask about.
 */
class LocalWalkFoldersTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `an empty folder is still a folder`() {
        val root = temp.newFolder("test")

        assertEquals(listOf(listOf("test")), LocalWalk.foldersUnder(root.absolutePath))
    }

    @Test
    fun `the tree comes back shallowest first`() {
        val root = temp.newFolder("Vision")
        File(root, "a/b").mkdirs()
        File(root, "c").mkdirs()
        File(root, "a/film.mkv").writeText("x")

        val found = LocalWalk.foldersUnder(root.absolutePath)

        assertEquals(
            listOf(listOf("Vision"), listOf("Vision", "a"), listOf("Vision", "a", "b"), listOf("Vision", "c")),
            found.sortedBy { it.joinToString("/") },
        )
        // A folder cannot be made before its parent, so the order is part of
        // the answer, not an accident of the walk.
        found.forEachIndexed { i, folder ->
            if (folder.size > 1) {
                assertTrue(
                    "$folder came before its parent",
                    found.take(i).contains(folder.dropLast(1)),
                )
            }
        }
    }

    @Test
    fun `a file on its own has no folders`() {
        val file = temp.newFile("film.mkv")

        assertEquals(emptyList<List<String>>(), LocalWalk.foldersUnder(file.absolutePath))
    }

    /** The same reason the file walk skips them: a loop would walk for ever. */
    @Test
    fun `a link is not followed`() {
        val root = temp.newFolder("Vision")
        val inner = File(root, "real").apply { mkdirs() }
        runCatching {
            java.nio.file.Files.createSymbolicLink(
                File(root, "loop").toPath(),
                root.toPath(),
            )
        }.onFailure { return }

        val found = LocalWalk.foldersUnder(root.absolutePath)

        assertTrue("the real folder is missing", found.contains(listOf("Vision", "real")))
        assertTrue("the link was followed", found.none { it.contains("loop") })
        assertTrue(inner.isDirectory)
    }
}
