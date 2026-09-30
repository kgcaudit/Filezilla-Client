package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that decide whether a server row fetches a preview, apart from
 * Android so they can be stated plainly and checked at every edge.
 *
 * Both matter for the same reason the transfer Wi-Fi rule does: a preview is
 * the whole file over FTP, so "yes" here is a download. Getting the cap or the
 * kind list wrong in the lenient direction turns opening a folder into
 * downloading it, and getting the Wi-Fi rule wrong spends mobile data the user
 * asked to keep for Wi-Fi.
 */
class ServerThumbEligibilityTest {

    private val underCap = 2L * 1024 * 1024
    private val overCap = ServerThumbnails.MAX_FETCH_BYTES + 1

    @Test
    fun `pictures and songs under the cap are eligible`() {
        assertTrue(serverThumbEligible(enabled = true, kind = FileKind.IMAGE, sizeBytes = underCap))
        assertTrue(serverThumbEligible(enabled = true, kind = FileKind.AUDIO, sizeBytes = underCap))
    }

    @Test
    fun `video is eligible at any size`() {
        // Only a prefix is fetched, so the file's own size is not the limit --
        // a 2 GB clip is as eligible as a small one.
        assertTrue(serverThumbEligible(true, FileKind.VIDEO, underCap))
        assertTrue(serverThumbEligible(true, FileKind.VIDEO, overCap))
        assertTrue(serverThumbEligible(true, FileKind.VIDEO, 2_000_000_000L))
        assertTrue(serverThumbEligible(true, FileKind.VIDEO, -1))
    }

    @Test
    fun `non-media kinds never are`() {
        for (kind in listOf(
            FileKind.DOCUMENT, FileKind.FOLDER, FileKind.ARCHIVE,
            FileKind.CODE, FileKind.APP, FileKind.OTHER, FileKind.COMIC,
        )) {
            assertFalse("$kind should not be eligible", serverThumbEligible(true, kind, underCap))
        }
    }

    @Test
    fun `an image over the cap is not`() {
        assertFalse(serverThumbEligible(true, FileKind.IMAGE, overCap))
        assertFalse(serverThumbEligible(true, FileKind.AUDIO, overCap))
    }

    @Test
    fun `the boundary is inclusive`() {
        assertTrue(serverThumbEligible(true, FileKind.IMAGE, ServerThumbnails.MAX_FETCH_BYTES))
    }

    @Test
    fun `a size the server did not give is allowed`() {
        assertTrue(serverThumbEligible(true, FileKind.IMAGE, -1))
    }

    @Test
    fun `nothing is eligible when the switch is off`() {
        assertFalse(serverThumbEligible(enabled = false, kind = FileKind.IMAGE, sizeBytes = underCap))
        assertFalse(serverThumbEligible(enabled = false, kind = FileKind.AUDIO, sizeBytes = -1))
        assertFalse(serverThumbEligible(enabled = false, kind = FileKind.VIDEO, sizeBytes = underCap))
    }

    @Test
    fun `the wifi rule allows anywhere when off and only unmetered when on`() {
        assertTrue("off, metered", serverThumbAllowed(wifiOnly = false, unmetered = false))
        assertTrue("off, unmetered", serverThumbAllowed(wifiOnly = false, unmetered = true))
        assertFalse("on, metered", serverThumbAllowed(wifiOnly = true, unmetered = false))
        assertTrue("on, unmetered", serverThumbAllowed(wifiOnly = true, unmetered = true))
    }

    @Test
    fun `the cap is a real ceiling, not zero`() {
        // A guard against the cap constant being fat-fingered to something
        // that makes every photo too big or every file small enough.
        assertEquals(16L * 1024 * 1024, ServerThumbnails.MAX_FETCH_BYTES)
    }
}
