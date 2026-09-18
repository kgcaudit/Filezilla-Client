package org.filezilla.android.storage

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadDestinationTest {

    private val tree: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATest")

    @Test
    fun `a plain folder round-trips`() {
        val decoded = DownloadDestination.decode(DownloadDestination(tree).encode())

        assertEquals(tree, decoded.tree)
        assertEquals(emptyList<String>(), decoded.subPath)
    }

    @Test
    fun `a sub-path round-trips`() {
        val original = DownloadDestination(tree, listOf("Vision", "clips", "2026"))

        val decoded = DownloadDestination.decode(original.encode())

        assertEquals(tree, decoded.tree)
        assertEquals(listOf("Vision", "clips", "2026"), decoded.subPath)
    }

    /**
     * Records written before folder downloads existed hold a bare tree URI.
     * They have to keep working, or a queued transfer would lose its
     * destination on upgrade.
     */
    @Test
    fun `a bare tree uri decodes as no sub-path`() {
        val decoded = DownloadDestination.decode(tree.toString())

        assertEquals(tree, decoded.tree)
        assertEquals(emptyList<String>(), decoded.subPath)
    }

    /** A name cannot smuggle in an extra folder level. */
    @Test
    fun `awkward names survive encoding`() {
        val names = listOf("a/b", "100% done", "#hash", "공유 자료", "a?b&c")

        val decoded = DownloadDestination.decode(DownloadDestination(tree, names).encode())

        assertEquals(names, decoded.subPath)
    }
}
