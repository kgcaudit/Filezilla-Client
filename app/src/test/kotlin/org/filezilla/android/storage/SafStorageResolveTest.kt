package org.filezilla.android.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files

/**
 * Turning a URI another app hands in for viewing into a real file.
 *
 * The viewers read a [File], so this is the step that makes one: a file URI is
 * already a path, a content URI with no path of its own is streamed into the
 * cache so even a provider that only offers bytes can be opened, and anything
 * unreadable is null rather than a crash on the way into a viewer.
 */
@RunWith(RobolectricTestRunner::class)
class SafStorageResolveTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val storage = SafStorage(context)

    @Test
    fun `a file URI that is there resolves to that file`() {
        val file = File(Files.createTempDirectory("open").toFile(), "song.mp3").apply { writeText("x") }

        val resolved = storage.resolveForViewing(Uri.fromFile(file))

        assertEquals(file.absolutePath, resolved?.absolutePath)
    }

    @Test
    fun `a file URI that is gone resolves to nothing`() {
        val file = File(Files.createTempDirectory("open").toFile(), "gone.mp3")

        assertNull(storage.resolveForViewing(Uri.fromFile(file)))
    }

    @Test
    fun `a content URI with only a stream is copied into the cache`() {
        val uri = Uri.parse("content://example.provider/media/song.mp3")
        shadowOf(context.contentResolver).registerInputStream(uri, "subtitle bytes".byteInputStream())

        val resolved = storage.resolveForViewing(uri)

        assertTrue("copied under the cache", resolved!!.absolutePath.startsWith(context.cacheDir.absolutePath))
        assertEquals("song.mp3", resolved.name)
        assertEquals("subtitle bytes", resolved.readText())
    }

    @Test
    fun `a URI that is neither file nor content resolves to nothing`() {
        assertNull(storage.resolveForViewing(Uri.parse("https://example.com/song.mp3")))
    }
}
