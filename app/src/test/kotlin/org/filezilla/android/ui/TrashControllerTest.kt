package org.filezilla.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.filezilla.android.AppGraph
import org.filezilla.android.data.AppPreferences
import org.filezilla.android.data.TrashEntry
import org.filezilla.android.files.StorageVolumes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * The phone's trash: that a deleted file is held rather than erased, can be
 * put back whole, and is gone for good once the trash is emptied -- plus the
 * selection bookkeeping the bulk actions turn on.
 *
 * The backing store is the app's own [AppPreferences], a process-wide
 * singleton, so each test clears the trash before and after itself rather than
 * trusting a fresh one.
 */
@RunWith(RobolectricTestRunner::class)
class TrashControllerTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private lateinit var prefs: AppPreferences
    private lateinit var volumes: StorageVolumes

    @Before
    fun setUp() {
        val graph = AppGraph.of(app)
        prefs = graph.preferences
        volumes = graph.volumes
        prefs.clearTrash()
    }

    @After
    fun tearDown() {
        prefs.clearTrash()
        scope.cancel()
        dispatcher.close()
        quiesceMainLooper()
    }

    private fun controller(
        recentSource: (String) -> String = { "" },
        relistLocalPanes: () -> Unit = {},
    ) = TrashController(app, volumes, prefs, scope, recentSource, relistLocalPanes)

    private fun fileIn(prefix: String, name: String, content: String): File {
        val dir = Files.createTempDirectory(prefix).toFile()
        return File(dir, name).apply { writeText(content) }
    }

    @Test
    fun `fileFor reads an absolute path as is and a bare name against the legacy folder`() {
        val controller = controller()

        val absolute = TrashEntry("/trash/here/kept.bin", "/was/here.bin", false, 0)
        assertEquals(File("/trash/here/kept.bin"), controller.fileFor(absolute))

        val legacy = TrashEntry("old-name.bin", "/was/here.bin", false, 0)
        val resolved = controller.fileFor(legacy)
        assertEquals("old-name.bin", resolved.name)
        assertTrue("under the legacy trash folder", resolved.absolutePath.endsWith("/trash/old-name.bin"))
    }

    @Test
    fun `stashing moves the file out of its folder and records it`() {
        val controller = controller()
        val src = fileIn("home", "doc.txt", "content")

        controller.stashLocal(src.absolutePath)

        assertFalse("the original is moved out", src.exists())
        controller.refresh()
        assertEquals(1, controller.entries.size)
        val entry = controller.entries.single()
        assertEquals(src.absolutePath, entry.originalPath)
        assertTrue("the stored copy exists", controller.fileFor(entry).exists())
    }

    @Test
    fun `restoring puts the file back and clears the record`() {
        val controller = controller()
        val src = fileIn("home", "doc.txt", "content")
        controller.stashLocal(src.absolutePath)
        controller.refresh()
        val entry = controller.entries.single()

        controller.restore(entry)
        scope.drain()

        assertTrue("the file is back", src.exists())
        assertTrue("the record is cleared", controller.entries.isEmpty())
        assertEquals("content", src.readText())
        assertTrue(prefs.trash().isEmpty())
    }

    @Test
    fun `restoring re-lists the local panes`() {
        var relisted = false
        val controller = controller(relistLocalPanes = { relisted = true })
        val src = fileIn("home", "doc.txt", "x")
        controller.stashLocal(src.absolutePath)
        controller.refresh()

        controller.restore(controller.entries.single())
        scope.drain()

        assertTrue(relisted)
    }

    @Test
    fun `two deleted files of one name both survive`() {
        val controller = controller()
        controller.stashLocal(fileIn("a", "same.txt", "A").absolutePath)
        controller.stashLocal(fileIn("b", "same.txt", "B").absolutePath)

        controller.refresh()

        assertEquals(2, controller.entries.size)
        val backing = controller.entries.map { controller.fileFor(it) }
        assertTrue("both backing files are present", backing.all { it.exists() })
        assertEquals("their stored paths are distinct", 2, backing.map { it.absolutePath }.toSet().size)
    }

    @Test
    fun `emptying erases every stored file and the whole list`() {
        val controller = controller()
        controller.stashLocal(fileIn("home", "x.txt", "x").absolutePath)
        controller.refresh()
        val stored = controller.fileFor(controller.entries.single())
        assertTrue(stored.exists())

        controller.empty()
        scope.drain()

        assertTrue(controller.entries.isEmpty())
        assertTrue(prefs.trash().isEmpty())
        assertFalse(stored.exists())
    }

    @Test
    fun `toggling a row enters selection mode and select-all fills it`() {
        val controller = controller()
        controller.stashLocal(fileIn("a", "one.txt", "1").absolutePath)
        controller.stashLocal(fileIn("b", "two.txt", "2").absolutePath)
        controller.refresh()
        val first = controller.entries.first()

        assertFalse(controller.selecting)
        controller.toggleSelected(first)
        assertTrue(controller.selecting)
        assertEquals(setOf(first.trashPath), controller.selection)

        // Tapping the same row again unticks it but stays in selection mode.
        controller.toggleSelected(first)
        assertTrue(controller.selecting)
        assertTrue(controller.selection.isEmpty())

        controller.toggleSelectAll()
        assertEquals(controller.entries.map { it.trashPath }.toSet(), controller.selection)

        controller.exitSelection()
        assertFalse(controller.selecting)
        assertTrue(controller.selection.isEmpty())
    }

    @Test
    fun `erasing the selected rows removes only those`() {
        val controller = controller()
        controller.stashLocal(fileIn("a", "keep.txt", "k").absolutePath)
        controller.stashLocal(fileIn("b", "drop.txt", "d").absolutePath)
        controller.refresh()
        val drop = controller.entries.first { it.originalPath.endsWith("drop.txt") }
        val keepFile = controller.fileFor(controller.entries.first { it.originalPath.endsWith("keep.txt") })
        val dropFile = controller.fileFor(drop)

        controller.toggleSelected(drop)
        controller.deleteSelectedForever()
        scope.drain()

        assertEquals(1, controller.entries.size)
        assertFalse(controller.selecting)
        assertFalse("the erased backing file is gone", dropFile.exists())
        assertTrue("the kept backing file remains", keepFile.exists())
        assertEquals(1, prefs.trash().size)
    }
}
