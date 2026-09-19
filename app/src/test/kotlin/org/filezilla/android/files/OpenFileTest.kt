package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowMimeTypeMap

/**
 * The type a tapped file is handed over as.
 *
 * Tapping a file on the phone used to start a download of a file that was
 * already on the phone, and with no download folder chosen that opened the
 * system folder picker -- a window the user could make no sense of, because
 * it was answering a question they had not asked.
 */
@RunWith(RobolectricTestRunner::class)
class OpenFileTest {

    private val types: ShadowMimeTypeMap
        get() = Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton())

    @Test
    fun `a known extension is handed over as its own type`() {
        types.addExtensionMimeTypeMapping("pdf", "application/pdf")

        assertEquals("application/pdf", OpenFile.mimeTypeOf("report.pdf"))
    }

    /** Extensions are written both ways and mean the same thing. */
    @Test
    fun `case in the extension makes no difference`() {
        types.addExtensionMimeTypeMapping("jpg", "image/jpeg")

        assertEquals("image/jpeg", OpenFile.mimeTypeOf("HOLIDAY.JPG"))
    }

    /** The last dot, not the first: "archive.tar.gz" is a gz. */
    @Test
    fun `the extension is the last one`() {
        types.addExtensionMimeTypeMapping("gz", "application/gzip")

        assertEquals("application/gzip", OpenFile.mimeTypeOf("archive.tar.gz"))
    }

    /**
     * The fallback is a wildcard rather than octet-stream on purpose: almost
     * nothing declares it can view an octet-stream, so the honest answer is
     * the one that makes the phone say no app can open the file.
     */
    @Test
    fun `an extension the phone has never heard of still offers a choice`() {
        assertEquals("*/*", OpenFile.mimeTypeOf("notes.qqqzzz"))
    }

    @Test
    fun `a name with no extension at all still offers a choice`() {
        assertEquals("*/*", OpenFile.mimeTypeOf("README"))
    }

    /** A dotfile's name is not its extension; ".bashrc" is not a "bashrc" file. */
    @Test
    fun `a dotfile is not treated as one long extension`() {
        types.addExtensionMimeTypeMapping("bashrc", "text/x-shellscript")

        assertEquals("*/*", OpenFile.mimeTypeOf(".bashrc"))
    }
}
