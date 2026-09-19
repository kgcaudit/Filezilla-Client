package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.filezilla.android.ui.BrowseListing
import org.filezilla.android.ui.BrowseOptions
import java.io.IOException

/**
 * Reading the device's storage, against real files.
 *
 * Plain JUnit with a temporary directory rather than Robolectric: this part
 * is `java.io.File` and nothing else, so a real filesystem tests it more
 * honestly than a simulated one and does it in milliseconds.
 */
class LocalFileSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val source = LocalFileSource(label = "test")

    private fun listing(): List<String> = source.list(temp.root.absolutePath).map { it.name }

    @Test
    fun `a folder lists its files and folders`() {
        temp.newFile("a.txt")
        temp.newFolder("Movie")

        assertEquals(setOf("a.txt", "Movie"), listing().toSet())
    }

    @Test
    fun `a file reports its size and a folder does not`() {
        temp.newFile("a.txt").writeText("12345")
        temp.newFolder("Movie")

        val rows = source.list(temp.root.absolutePath).associateBy { it.name }

        assertEquals(5L, rows.getValue("a.txt").size)
        assertFalse(rows.getValue("a.txt").isDirectory)
        // A directory's length() is whatever the filesystem keeps its index
        // in, not a size worth showing, so it reports unknown -- which is what
        // the FTP side does and what the row already knows how to draw.
        assertEquals(-1L, rows.getValue("Movie").size)
        assertTrue(rows.getValue("Movie").isDirectory)
    }

    @Test
    fun `a file carries a timestamp the screen can show`() {
        temp.newFile("a.txt")

        val entry = source.list(temp.root.absolutePath).single()

        assertTrue("expected a timestamp", entry.hasDate)
        assertTrue("expected a time of day, not just a date", entry.hasTime)
    }

    @Test
    fun `an empty folder lists nothing rather than failing`() {
        assertEquals(emptyList<String>(), listing())
    }

    /**
     * Each of these used to be the same answer -- a null return -- and the
     * screen has something different to say about every one of them. A folder
     * that is gone, one that is not a folder, and one that is simply empty
     * are three different messages, and telling the user the wrong one sends
     * them looking in the wrong place.
     */
    @Test
    fun `a folder that is not there says so`() {
        val error = assertThrows(IOException::class.java) {
            source.list(temp.root.absolutePath + "/missing")
        }

        assertTrue(error.message.orEmpty(), "missing" in error.message.orEmpty())
    }

    @Test
    fun `a file is not a folder and says so`() {
        val file = temp.newFile("a.txt")

        val error = assertThrows(IOException::class.java) { source.list(file.absolutePath) }

        assertTrue(error.message.orEmpty(), "not a folder" in error.message.orEmpty())
    }

    /** The path is normalized, so a doubled slash is not a missing folder. */
    @Test
    fun `an untidy path still finds the folder`() {
        temp.newFile("a.txt")

        assertEquals(listOf("a.txt"), source.list(temp.root.absolutePath + "//./").map { it.name })
    }

    // ------------------------------------- sharing the remote side's ordering

    /**
     * The rows come back as the type the FTP listing uses, so the sorting and
     * filtering already written for the remote pane applies unchanged. That
     * is the whole reason for reusing the type, so it is worth a test rather
     * than an assumption.
     */
    @Test
    fun `the existing arrange puts local folders first`() {
        temp.newFile("a.txt")
        temp.newFolder("Zzz")

        val rows = BrowseListing.arrange(
            source.list(temp.root.absolutePath),
            BrowseOptions(),
            filter = "",
        )

        assertEquals(listOf("Zzz", "a.txt"), rows.map { it.name })
    }

    @Test
    fun `dotfiles are hidden unless asked for, as on the remote side`() {
        temp.newFile(".hidden")
        temp.newFile("shown.txt")

        val hidden = BrowseListing.arrange(source.list(temp.root.absolutePath), BrowseOptions(), "")
        val shown = BrowseListing.arrange(
            source.list(temp.root.absolutePath),
            BrowseOptions(showHidden = true),
            "",
        )

        assertEquals(listOf("shown.txt"), hidden.map { it.name })
        assertEquals(listOf(".hidden", "shown.txt"), shown.map { it.name })
    }

    // ------------------------------------------------------- volume roots

    /**
     * Android hands out `/storage/XXXX-XXXX/Android/data/<package>/files` and
     * offers no supported way below API 30 to ask for the volume root, so it
     * is cut off in front of `/Android/`. Cutting a fixed number of segments
     * instead would give a different answer on the next vendor's layout.
     */
    @Test
    fun `a volume root is what sits in front of the Android folder`() {
        assertEquals(
            "/storage/1A2B-3C4D",
            volumeRootOf("/storage/1A2B-3C4D/Android/data/org.filezilla.android/files"),
        )
        assertEquals(
            "/storage/emulated/0",
            volumeRootOf("/storage/emulated/0/Android/data/org.filezilla.android/files"),
        )
    }

    @Test
    fun `a path of another shape gives no root rather than a wrong one`() {
        assertNull(volumeRootOf("/data/user/0/org.filezilla.android/files"))
        // Nothing in front of it is not a volume either.
        assertNull(volumeRootOf("/Android/data/org.filezilla.android/files"))
    }
}
