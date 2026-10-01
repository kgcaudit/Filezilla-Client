package org.filezilla.android.files

import org.filezilla.android.data.RecentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * How a recents row is matched to a file, and how a moved one is found again.
 *
 * The two together are what make the recents open the right file: the match
 * tells the recorded file from a different one now at its path, and the locator
 * follows it when it has moved -- and refuses a same-named impostor of another
 * size.
 */
class RecentFilesTest {

    private fun entry(size: Long, modified: Long) =
        RecentEntry(path = "/old/clip.mp4", time = 1L, size = size, modified = modified)

    // ---------------------------------------------------------- availability

    @Test
    fun `present when size and time both match`() {
        val e = entry(size = 10, modified = 1000)
        assertEquals(RecentAvailability.PRESENT, recentAvailability(e, exists = true, size = 10, modified = 1000))
    }

    @Test
    fun `missing when nothing is there`() {
        assertEquals(RecentAvailability.MISSING, recentAvailability(entry(10, 1000), exists = false, size = 0, modified = 0))
    }

    @Test
    fun `changed when a different file sits at the path`() {
        val e = entry(size = 10, modified = 1000)
        // Same path, different bytes: a size or a time that differs is enough.
        assertEquals(RecentAvailability.CHANGED, recentAvailability(e, exists = true, size = 20, modified = 1000))
        assertEquals(RecentAvailability.CHANGED, recentAvailability(e, exists = true, size = 10, modified = 2000))
    }

    @Test
    fun `a legacy entry counts as present whenever something is there`() {
        val legacy = RecentEntry(path = "/old/x.txt", time = 1L) // no fingerprint
        assertEquals(RecentAvailability.PRESENT, recentAvailability(legacy, exists = true, size = 999, modified = 42))
        assertEquals(RecentAvailability.MISSING, recentAvailability(legacy, exists = false, size = 0, modified = 0))
    }

    // --------------------------------------------------------------- locator

    private fun write(dir: File, name: String, bytes: ByteArray): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `a moved file is found by its name, size and time`() {
        val root = Files.createTempDirectory("recent").toFile()
        val moved = write(File(root, "Movies/2024"), "clip.mp4", ByteArray(1234) { 7 })
        val found = RecentLocator.find(listOf(root), "clip.mp4", moved.length(), moved.lastModified())
        assertEquals(moved, found)
    }

    @Test
    fun `a same-named file of a different size is not mistaken for it`() {
        val root = Files.createTempDirectory("recent").toFile()
        val original = write(File(root, "a"), "clip.mp4", ByteArray(1000) { 1 })
        // An impostor with the same name but different bytes, elsewhere.
        write(File(root, "b"), "clip.mp4", ByteArray(2000) { 2 })
        val found = RecentLocator.find(listOf(root), "clip.mp4", original.length(), original.lastModified())
        assertEquals(original, found)
    }

    @Test
    fun `nothing is returned when no file matches`() {
        val root = Files.createTempDirectory("recent").toFile()
        write(File(root, "a"), "other.mp4", ByteArray(10))
        assertNull(RecentLocator.find(listOf(root), "clip.mp4", 10, 0))
    }

    @Test
    fun `a legacy entry with no fingerprint cannot be located`() {
        val root = Files.createTempDirectory("recent").toFile()
        write(File(root, "a"), "clip.mp4", ByteArray(10))
        assertNull(RecentLocator.find(listOf(root), "clip.mp4", size = -1, modified = 0))
    }
}
