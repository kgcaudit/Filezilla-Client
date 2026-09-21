package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * That an archive cannot write outside the folder it was unpacked into.
 *
 * The names in an archive are the only record of its folders -- ALZip
 * writes no directory entries, so the path in the name is all there is --
 * and those names came from a file somebody else made. Joining one to a
 * local folder without looking is how a downloaded archive drops a file
 * into another app's storage.
 */
class ArchivePathsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root: File get() = temp.root

    private fun resolved(path: String): String? =
        ArchivePaths.resolve(root, path)?.path?.removePrefix(root.canonicalPath)

    @Test
    fun `an ordinary name lands inside`() {
        assertEquals("/알집.txt", resolved("알집.txt"))
        assertEquals("/말똥가리/알집.txt", resolved("말똥가리/알집.txt"))
    }

    @Test
    fun `a name that climbs out is refused`() {
        assertNull(resolved("../escaped.txt"))
        assertNull(resolved("../../../../etc/passwd"))
        assertNull(resolved("말똥가리/../../escaped.txt"))
    }

    @Test
    fun `climbing and coming back is allowed, because it lands inside`() {
        // Checked by where it ends up rather than how it is spelt: a rule
        // about the spelling is one somebody can spell their way around.
        assertEquals("/알집.txt", resolved("말똥가리/../알집.txt"))
    }

    @Test
    fun `an absolute name is refused`() {
        assertNull(resolved("/etc/passwd"))
        assertNull(resolved("/data/data/other.app/databases/x"))
    }

    @Test
    fun `a windows drive is refused`() {
        // Absolute somewhere else, and meaningless here either way.
        assertNull(resolved("C:/Windows/System32/x"))
        assertNull(resolved("C:\\Windows\\System32\\x"))
    }

    @Test
    fun `backslashes are read as separators`() {
        // Some archivers write them, and left alone they become part of
        // the file name instead of a folder.
        assertEquals("/말똥가리/알집.txt", resolved("말똥가리\\알집.txt"))
    }

    @Test
    fun `nothing is not a destination`() {
        assertNull(resolved(""))
        assertNull(resolved("   ".trim()))
        assertNull(resolved("/"))
    }

    @Test
    fun `folders come from the paths, shallowest first`() {
        val entries = listOf(
            ArchiveEntry("말똥가리/알집.txt"),
            ArchiveEntry("말똥가리.alz"),
            ArchiveEntry("a/b/c/deep.txt"),
        )

        assertEquals(
            listOf("a", "말똥가리", "a/b", "a/b/c"),
            ArchivePaths.foldersIn(entries),
        )
    }

    @Test
    fun `a file at the top implies no folder at all`() {
        assertEquals(emptyList<String>(), ArchivePaths.foldersIn(listOf(ArchiveEntry("알집.txt"))))
    }

    @Test
    fun `a recorded directory counts as a folder too`() {
        // Written from the spec they do exist, even though ALZip does not
        // write them -- so both shapes have to produce the same answer.
        assertEquals(
            listOf("사진"),
            ArchivePaths.foldersIn(listOf(ArchiveEntry("사진/", isDirectory = true))),
        )
    }
}
