package org.filezilla.android.ui

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Where the player reads each media item from.
 *
 * The ordinary item is a file and plays from its own path. A handed-in stream
 * plays from the URI another app granted, while a stand-in file carries only
 * its name -- so the test pins that the stand-in's path is never what gets
 * played, which is the whole point of streaming rather than copying.
 */
@RunWith(RobolectricTestRunner::class)
class MediaViewerUriTest {

    @Test
    fun `a plain file plays from its own path`() {
        val file = File("/storage/emulated/0/Music/song.mp3")
        val viewer = MainViewModel.MediaViewer(items = listOf(file), index = 0)

        assertEquals(Uri.fromFile(file), viewer.uriOf(0))
    }

    @Test
    fun `a stream plays from the handed-in uri, not the stand-in file`() {
        val standIn = File("/data/cache/stream/song.mp3")
        val stream = Uri.parse("content://cloud.provider/document/42")
        val viewer = MainViewModel.MediaViewer(
            items = listOf(standIn),
            index = 0,
            sources = listOf(stream),
        )

        assertEquals(stream, viewer.uriOf(0))
    }
}
