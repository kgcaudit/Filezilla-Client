package org.filezilla.android.endtoend

import org.filezilla.android.files.FileMode
import org.filezilla.android.ui.PaneId
import org.filezilla.ftp.listing.DirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Changing a file's permissions on a server, end to end.
 *
 * `SITE CHMOD` had been written, tested against a real server and left
 * with no way to reach it: the properties dialog would tell you a file was
 * `-rw-------` and that was as far as the app went. Which is the moment it
 * is least useful -- permissions get looked up when something has just
 * been refused, and being told why without being able to do anything is a
 * trip to a computer.
 */
@RunWith(RobolectricTestRunner::class)
class ChangeModeTest : AppAgainstAServer() {

    private fun modeOf(name: String): String {
        val bits = Files.getPosixFilePermissions(File(onServer, name).toPath())
        var value = 0
        for (one in bits) {
            value = value or when (one.name) {
                "OWNER_READ" -> 0b100_000_000
                "OWNER_WRITE" -> 0b010_000_000
                "OWNER_EXECUTE" -> 0b001_000_000
                "GROUP_READ" -> 0b000_100_000
                "GROUP_WRITE" -> 0b000_010_000
                "GROUP_EXECUTE" -> 0b000_001_000
                "OTHERS_READ" -> 0b000_000_100
                "OTHERS_WRITE" -> 0b000_000_010
                else -> 0b000_000_001
            }
        }
        return value.toString(8).padStart(3, '0')
    }

    private fun rowFor(model: org.filezilla.android.ui.MainViewModel, name: String) =
        model.pane(PaneId.LEFT).entries.first { it.name == name }

    @Test
    fun `a file's permissions are changed on the server`() {
        val file = File(onServer, "locked.txt")
        file.writeText("x")
        Files.setPosixFilePermissions(file.toPath(), Files.getPosixFilePermissions(file.toPath()))
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(false, false)

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "locked.txt" } }

        val row = rowFor(model, "locked.txt")
        model.changeModeOf(PaneId.LEFT, row)
        assertNotNull("the dialog should have something to show", model.changingMode)

        model.applyMode(PaneId.LEFT, row, FileMode(0b110_100_100), null)
        waitFor("the pane to settle") { !model.pane(PaneId.LEFT).loading }

        assertEquals("644", modeOf("locked.txt"))
        assertNull("the dialog should close", model.changingMode)
        assertNull("changing permissions should not leave an error", model.pane(PaneId.LEFT).error)
    }

    @Test
    fun `what the server says is what the dialog starts from`() {
        val file = File(onServer, "readable.txt")
        file.writeText("x")
        Files.setPosixFilePermissions(
            file.toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---"),
        )

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "readable.txt" } }

        // The whole point of reading it: a dialog that opened on a default
        // would quietly rewrite whatever was actually there the moment it
        // was confirmed.
        assertEquals("750", FileMode.of(rowFor(model, "readable.txt").permissions).toString())
    }

    @Test
    fun `a sticky folder keeps its sticky bit`() {
        val folder = File(onServer, "shared")
        folder.mkdirs()
        Files.setPosixFilePermissions(
            folder.toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"),
        )
        // The bit that stops people deleting each other's files. It is not
        // editable here, so the only two possible behaviours are keeping it
        // and destroying it.
        Runtime.getRuntime().exec(arrayOf("chmod", "1777", folder.absolutePath)).waitFor()
        assertTrue("could not set up a sticky folder", File(onServer, "shared").canExecute())

        val model = model()
        openOnServer(model, PaneId.LEFT, savedSite())
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == "shared" } }

        val row = rowFor(model, "shared")
        val extra = FileMode.extraDigitIn(row.permissions)
        assertEquals("the listing should have shown a sticky bit", "1", extra)

        // Take away group write and nothing else.
        model.applyMode(PaneId.LEFT, row, FileMode(0b111_101_111), extra)
        waitFor("the pane to settle") { !model.pane(PaneId.LEFT).loading }

        val after = Runtime.getRuntime()
            .exec(arrayOf("stat", "-c", "%a", File(onServer, "shared").absolutePath))
            .inputStream.bufferedReader().readText().trim()
        assertEquals("the sticky bit was sent away with the change", "1757", after)
    }

    @Test
    fun `the phone's side is not offered permissions it cannot set`() {
        val folder = phone.newFolder("here")
        File(folder, "one.txt").writeText("x")

        val model = model()
        openOnPhone(model, PaneId.LEFT, folder)

        // Storage-framework documents have no permission bits. Offering
        // this and then failing would be worse than not offering it.
        model.changeModeOf(PaneId.LEFT, DirectoryEntry(name = "one.txt"))

        assertNull(model.changingMode)
    }
}
