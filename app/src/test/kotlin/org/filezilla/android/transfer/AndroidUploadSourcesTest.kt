package org.filezilla.android.transfer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Finding a moved upload source again, against real files.
 *
 * The media index is empty under the test, so these exercise the walk and the
 * refusals: a file still in place is left alone, a moved file is found and its
 * new path returned, a deleted one is reported missing, and two indistinguishable
 * copies are a tie rather than a guess.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidUploadSourcesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val volume = Files.createTempDirectory("volume").toFile()
    private val sources = AndroidUploadSources(context) { listOf(volume.absolutePath) }

    private fun fileUri(file: File) = Uri.fromFile(file)

    private fun write(relative: String, content: String): File {
        val file = File(volume, relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `a source still in place is returned unchanged`() {
        val file = write("Download/film.srt", "subtitle")
        val stored = fileUri(file)

        assertEquals(stored, sources.resolve(stored, file.length()))
    }

    @Test
    fun `a moved source is found again at its new path`() {
        val original = write("Download/film.srt", "subtitle")
        val size = original.length()
        val stored = fileUri(original)
        // Moved to another folder on the same volume.
        val moved = File(volume, "Movies/film.srt")
        moved.parentFile?.mkdirs()
        original.copyTo(moved)
        original.delete()

        val resolved = sources.resolve(stored, size)

        assertEquals(moved.absolutePath, File(requireNotNull(resolved.path)).absolutePath)
    }

    @Test
    fun `a deleted source is reported missing`() {
        val file = write("Download/film.srt", "subtitle")
        val stored = fileUri(file)
        file.delete()

        assertThrows(SourceMissingException::class.java) {
            sources.resolve(stored, 8)
        }
    }

    @Test
    fun `two indistinguishable copies are a tie, not a guess`() {
        val original = write("Download/film.srt", "subtitle")
        val size = original.length()
        val stored = fileUri(original)
        // Two identical copies elsewhere, original gone: no safe single answer.
        write("A/film.srt", "subtitle")
        write("B/film.srt", "subtitle")
        original.delete()

        assertThrows(SourceMissingException::class.java) {
            sources.resolve(stored, size)
        }
    }

    @Test
    fun `size tells the right copy from a same-named decoy`() {
        val original = write("Download/film.srt", "the real subtitle bytes")
        val size = original.length()
        val stored = fileUri(original)
        write("Decoy/film.srt", "different length entirely, a decoy")
        val moved = File(volume, "Movies/film.srt")
        moved.parentFile?.mkdirs()
        original.copyTo(moved)
        original.delete()

        val resolved = sources.resolve(stored, size)

        assertEquals(moved.absolutePath, File(requireNotNull(resolved.path)).absolutePath)
    }
}
