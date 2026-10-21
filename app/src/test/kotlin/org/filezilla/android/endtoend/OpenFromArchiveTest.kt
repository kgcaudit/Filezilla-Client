package org.filezilla.android.endtoend

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.filezilla.android.AppGraph
import org.filezilla.android.data.FakePasswordCipher
import org.filezilla.android.data.KeystorePasswordCipher
import org.filezilla.android.files.OpenFile
import org.filezilla.android.ui.MainViewModel
import org.filezilla.android.ui.PaneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Opening a file from inside an archive, end to end.
 *
 * A tap on a text file inside a zip is meant to unpack it and hand it to
 * whatever reads text. The unpacked copy used to be written to a cache
 * folder this app never declared to the FileProvider, so the URI that the
 * "open with" chooser needs could not be built at all -- and the phone
 * answered the tap with "no app can open this file", for a plain .txt that
 * a dozen apps would have opened. The copy now goes in the viewing cache,
 * which is the folder the provider does declare.
 */
@RunWith(RobolectricTestRunner::class)
class OpenFromArchiveTest {

    @get:Rule
    val phone = TemporaryFolder()

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun start() {
        AppGraph.sealPasswordsWith = { FakePasswordCipher() }
        AppGraph.forget()
        File(application.cacheDir, "viewing").deleteRecursively()
    }

    @After
    fun stop() {
        AppGraph.forget()
        AppGraph.sealPasswordsWith = { KeystorePasswordCipher() }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        // The archive opens on the injected dispatcher, which this test sets to
        // Unconfined -- so the background work runs inline and its continuation
        // lands on the main looper. Pumping the looper therefore completes it
        // deterministically; there is no real thread to wait on, so no
        // wall-clock sleep and no race under parallel load (which is what made
        // this flaky before the dispatcher was injectable).
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun zipWith(name: String, contents: String): File {
        val zip = phone.newFile("bundle.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(name))
            out.write(contents.toByteArray())
            out.closeEntry()
        }
        return zip
    }

    @Test
    fun `a text file opened from inside a zip has an intent that can be handed off`() {
        val zip = zipWith("notes.txt", "hello from inside the archive")

        val model = MainViewModel(application, kotlinx.coroutines.Dispatchers.Unconfined)
        model.openArchive(PaneId.LEFT, zip, zip.parentFile!!.path)
        waitFor("the archive to open") { model.inArchive(PaneId.LEFT) }

        model.archiveTap(PaneId.LEFT, "notes.txt")
        waitFor("the unpacked copy") { model.readyToOpen != null }

        val copy = model.readyToOpen!!
        assertEquals("hello from inside the archive", copy.readText())
        assertEquals("txt", copy.extension)

        // The regression: the copy has to sit somewhere the FileProvider
        // declares, or the URI the chooser needs cannot be built and the
        // phone says nothing can open a plain text file. A null intent here
        // is exactly what the user saw.
        val intent = OpenFile.intentFor(application, copy)
        assertNotNull("no shareable URI could be built for the unpacked file", intent)
        assertTrue(
            "the copy should live under the viewing cache the provider declares",
            AppGraph.of(application).viewCache.holds(copy),
        )
    }

    private fun nestedZip(): File {
        // outer.zip -> inner.zip -> notes.txt
        val innerBytes = java.io.ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("notes.txt"))
                z.write("deep inside".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        val outer = phone.newFile("outer.zip")
        ZipOutputStream(outer.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("inner.zip"))
            z.write(innerBytes)
            z.closeEntry()
        }
        return outer
    }

    @Test
    fun `backing out of a nested archive returns to the outer one, not the cache`() {
        val outer = nestedZip()

        val model = MainViewModel(application, kotlinx.coroutines.Dispatchers.Unconfined)
        model.openArchive(PaneId.LEFT, outer, outer.parentFile!!.path)
        waitFor("the outer archive's rows") {
            model.inArchive(PaneId.LEFT) && model.pane(PaneId.LEFT).entries.isNotEmpty() ||
                model.archiveOutcome != null
        }
        assertEquals("open should not have failed", null, model.archiveOutcome)
        assertTrue(model.pane(PaneId.LEFT).entries.any { it.name == "inner.zip" })

        // Tap the inner archive: it is unpacked to the cache and opened as the
        // nested archive, the same as MainActivity does when readyToOpen is a
        // browsable archive.
        model.archiveTap(PaneId.LEFT, "inner.zip")
        waitFor("the inner copy") { model.readyToOpen != null }
        val innerCopy = model.readyToOpen!!
        model.openedReady()
        model.openArchive(PaneId.LEFT, innerCopy, innerCopy.parent ?: "")
        waitFor("the inner archive") {
            model.pane(PaneId.LEFT).entries.any { it.name == "notes.txt" }
        }

        // Back out of the inner archive's root.
        model.archiveUp(PaneId.LEFT)

        // The regression: it used to list the cache folder the inner copy sat
        // in. It should be the outer archive again.
        assertTrue("should still be inside an archive", model.inArchive(PaneId.LEFT))
        assertTrue(
            "backing out should show the outer archive's rows",
            model.pane(PaneId.LEFT).entries.any { it.name == "inner.zip" },
        )
        assertFalse(
            "the pane must not have wandered into the viewing cache",
            AppGraph.of(application).viewCache.holds(File(model.pane(PaneId.LEFT).path, "x")),
        )
    }

    @Test
    fun `the same entry a second time reuses the copy already unpacked`() {
        val zip = zipWith("notes.txt", "hello")

        val model = MainViewModel(application, kotlinx.coroutines.Dispatchers.Unconfined)
        model.openArchive(PaneId.LEFT, zip, zip.parentFile!!.path)
        waitFor("the archive to open") { model.inArchive(PaneId.LEFT) }

        model.archiveTap(PaneId.LEFT, "notes.txt")
        waitFor("the first copy") { model.readyToOpen != null }
        val first = model.readyToOpen!!
        model.openedReady()

        model.archiveTap(PaneId.LEFT, "notes.txt")
        waitFor("the held copy") { model.readyToOpen != null }

        assertEquals(
            "the same version should reopen the copy already unpacked",
            first,
            model.readyToOpen,
        )
    }
}
