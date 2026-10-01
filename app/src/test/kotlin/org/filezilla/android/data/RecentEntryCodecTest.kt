package org.filezilla.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a recents row survives the round trip to storage and back, and that a
 * row written before the fingerprint existed still reads.
 */
class RecentEntryCodecTest {

    @Test
    fun `a fingerprinted entry round-trips`() {
        val entry = RecentEntry(path = "/storage/emulated/0/Movies/a b.mp4", time = 123L, size = 456L, modified = 789L)
        assertEquals(entry, RecentEntry.decode(entry.encode()))
        assertTrue(entry.hasFingerprint)
    }

    @Test
    fun `a path with unusual characters survives`() {
        val entry = RecentEntry(path = "/스토리지/영상 (2024)/x=1&y.mkv", time = 9L, size = 1L, modified = 2L)
        assertEquals(entry, RecentEntry.decode(entry.encode()))
    }

    @Test
    fun `a legacy two-field row reads with no fingerprint`() {
        // The old format: time, then the path, one NUL between.
        val decoded = RecentEntry.decode("1726000000000\u0000/storage/emulated/0/notes.txt")
        assertEquals(RecentEntry("/storage/emulated/0/notes.txt", 1_726_000_000_000L, size = -1, modified = 0), decoded)
        assertFalse("a legacy row has no fingerprint to check", decoded!!.hasFingerprint)
    }

    @Test
    fun `malformed rows are dropped rather than throwing`() {
        assertNull(RecentEntry.decode(""))
        assertNull(RecentEntry.decode("notanumber\u0000/a/b"))
        assertNull(RecentEntry.decode("123\u0000456\u0000789\u0000")) // empty path
    }
}
