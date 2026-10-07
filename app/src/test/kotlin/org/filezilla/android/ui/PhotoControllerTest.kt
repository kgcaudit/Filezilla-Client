package org.filezilla.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Which open photo the info sheet will let the user edit.
 *
 * The rule the code states in words -- a real, writable image the user owns,
 * and not a throwaway copy of a server file under the cache -- is the part
 * worth pinning: get it wrong the lenient way and the app offers to rewrite a
 * cache file the user never keeps; get it wrong the strict way and a genuine
 * photo turns read-only for no reason the user can see.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoControllerTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)

    @After
    fun tearDown() {
        scope.cancel()
        dispatcher.close()
        quiesceMainLooper()
    }

    private fun opened(file: File): PhotoController {
        val controller = PhotoController(app, scope)
        controller.open(file)
        scope.drain()
        return controller
    }

    @Test
    fun `a writable local jpg is editable`() {
        val dir = Files.createTempDirectory("photos").toFile()
        val file = File(dir, "pic.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertTrue(opened(file).info!!.editable)
    }

    @Test
    fun `a photo under the cache is read-only`() {
        val file = File(app.cacheDir, "cached.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertFalse(opened(file).info!!.editable)
    }

    @Test
    fun `a format whose EXIF cannot be written is read-only`() {
        val dir = Files.createTempDirectory("photos").toFile()
        val file = File(dir, "note.txt").apply { writeBytes(byteArrayOf(1)) }

        assertFalse(opened(file).info!!.editable)
    }

    @Test
    fun `closing forgets the sheet`() {
        val dir = Files.createTempDirectory("photos").toFile()
        val file = File(dir, "pic.jpg").apply { writeBytes(byteArrayOf(1)) }
        val controller = opened(file)

        controller.close()
        assertNull(controller.info)
    }
}
