package org.filezilla.android.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PartialFilesTest {

    private lateinit var partials: PartialFiles

    @Before
    fun setUp() {
        partials = PartialFiles(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `a partial file reports the length the resume offset is checked against`() {
        val file = partials.forTransfer("abc")
        file.writeBytes(ByteArray(1_234))
        assertEquals(1_234L, partials.sizeOf("abc"))
    }

    @Test
    fun `a missing partial file reports no size rather than zero`() {
        // ResumeSafety treats null and 0 the same way, but only because it is
        // told the truth: a zero here would be a claim that the file exists.
        assertNull(partials.sizeOf("never-written"))
    }

    @Test
    fun `pruning keeps files a live transfer still claims`() {
        partials.forTransfer("live").writeBytes(ByteArray(10))
        partials.forTransfer("orphan").writeBytes(ByteArray(10))

        partials.pruneOrphans(setOf("live"))

        assertTrue(partials.forTransfer("live").isFile)
        assertFalse(partials.forTransfer("orphan").isFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an id that could escape the partials directory is refused`() {
        partials.forTransfer("../../databases/filezilla.db")
    }
}
