package org.filezilla.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That the recents and trash lists never read back two rows with one key.
 *
 * Both screens are keyed by path, and a keyed list crashes when a key repeats.
 * An older build's path-healing could leave a duplicate stored, and a legacy
 * trash row kept only a bare name that two files could share -- so the read has
 * to heal it, or the screen crashes on data written before the fix. Keeping the
 * first row means the more recent one stays, since both lists are newest-first.
 */
@RunWith(RobolectricTestRunner::class)
class RecentsDedupTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        prefs = AppPreferences(context)
        prefs.clearRecents()
        prefs.clearTrash()
    }

    @Test
    fun `two recents on one path read back as one, the first kept`() {
        prefs.setRecents(
            listOf(
                RecentEntry(path = "/v/x.mp4", time = 200L, size = 10L, modified = 1L),
                RecentEntry(path = "/v/x.mp4", time = 100L, size = 10L, modified = 1L),
                RecentEntry(path = "/v/y.mp4", time = 50L, size = 20L, modified = 2L),
            ),
        )

        val read = prefs.recents()

        assertEquals(listOf("/v/x.mp4", "/v/y.mp4"), read.map { it.path })
        assertEquals("the more recent of the two is the one kept", 200L, read.first().time)
    }

    @Test
    fun `two trash rows on one path read back as one`() {
        prefs.addTrash("same", "/was/a.txt", false, 100L)
        prefs.addTrash("same", "/was/b.txt", false, 200L)

        val read = prefs.trash()

        assertEquals(1, read.size)
        assertEquals("same", read.single().trashPath)
    }
}
