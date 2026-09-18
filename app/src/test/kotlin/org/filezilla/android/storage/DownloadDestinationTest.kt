package org.filezilla.android.storage

import android.net.Uri
import org.filezilla.android.storage.ConflictChoice
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

    // ------------------------------------------------------ conflict choice

    /**
     * The choice has to survive the journal, because it is read at publish
     * time -- minutes or a restart after the user made it. Losing it here
     * would silently fall back to keeping both, which is the behaviour the
     * dialog exists to replace.
     */
    @Test
    fun `every choice round-trips`() {
        for (choice in ConflictChoice.entries) {
            val original = DownloadDestination(tree, listOf("Vision"), choice)

            assertEquals("$choice", choice, DownloadDestination.decode(original.encode()).onConflict)
        }
    }

    @Test
    fun `a choice round-trips with no sub-path too`() {
        val original = DownloadDestination(tree, emptyList(), ConflictChoice.OVERWRITE)

        val decoded = DownloadDestination.decode(original.encode())

        assertEquals(tree, decoded.tree)
        assertEquals(emptyList<String>(), decoded.subPath)
        assertEquals(ConflictChoice.OVERWRITE, decoded.onConflict)
    }

    @Test
    fun `the sub-path still round-trips beside a choice`() {
        val names = listOf("Vision", "a/b", "100% done", "공유 자료")
        val original = DownloadDestination(tree, names, ConflictChoice.SKIP)

        val decoded = DownloadDestination.decode(original.encode())

        assertEquals(names, decoded.subPath)
        assertEquals(ConflictChoice.SKIP, decoded.onConflict)
    }

    /** Records written before the dialog existed must keep working. */
    @Test
    fun `an older record decodes to the safe default`() {
        val old = DownloadDestination(tree, listOf("Vision"))
        // What the previous version wrote: the path, with no choice after it.
        val legacy = tree.buildUpon().encodedFragment("Vision").build().toString()

        val decoded = DownloadDestination.decode(legacy)

        assertEquals(old.subPath, decoded.subPath)
        assertEquals(ConflictChoice.KEEP_BOTH, decoded.onConflict)
    }

    /** Keeping both is the only choice that cannot destroy a file. */
    @Test
    fun `the default never overwrites or skips`() {
        assertEquals(ConflictChoice.KEEP_BOTH, ConflictChoice.DEFAULT)
        assertEquals(ConflictChoice.KEEP_BOTH, DownloadDestination.decode(tree.toString()).onConflict)
    }
}
