package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowMimeTypeMap

/**
 * What a set of files is offered to the share sheet as.
 *
 * The type is what decides which apps appear, so it is worth getting right:
 * a wildcard for three photos puts the gallery somewhere below every app on
 * the phone that accepts anything, which is most of them.
 */
@RunWith(RobolectricTestRunner::class)
class ShareFilesTest {

    private val types: ShadowMimeTypeMap
        get() = Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton())

    @Test
    fun `one file goes as its own type`() {
        types.addExtensionMimeTypeMapping("pdf", "application/pdf")

        assertEquals("application/pdf", ShareFiles.commonTypeOf(listOf("report.pdf")))
    }

    @Test
    fun `files of one type go as that type`() {
        types.addExtensionMimeTypeMapping("jpg", "image/jpeg")

        assertEquals("image/jpeg", ShareFiles.commonTypeOf(listOf("a.jpg", "b.jpg")))
    }

    /** A JPEG and a PNG are both pictures, and the gallery should still show up. */
    @Test
    fun `files of one family go as that family`() {
        types.addExtensionMimeTypeMapping("jpg", "image/jpeg")
        types.addExtensionMimeTypeMapping("png", "image/png")

        assertEquals("image/*", ShareFiles.commonTypeOf(listOf("a.jpg", "b.png")))
    }

    @Test
    fun `files with nothing in common go as a wildcard`() {
        types.addExtensionMimeTypeMapping("jpg", "image/jpeg")
        types.addExtensionMimeTypeMapping("pdf", "application/pdf")

        assertEquals("*/*", ShareFiles.commonTypeOf(listOf("a.jpg", "b.pdf")))
    }

    /**
     * One unknown name drags the rest with it: there is no family it belongs
     * to, so claiming the others' family would be claiming something about a
     * file nothing knows anything about.
     */
    @Test
    fun `one unknown file makes the whole set a wildcard`() {
        types.addExtensionMimeTypeMapping("jpg", "image/jpeg")

        assertEquals("*/*", ShareFiles.commonTypeOf(listOf("a.jpg", "notes.qqqzzz")))
    }

    @Test
    fun `nothing to share is a wildcard rather than a crash`() {
        assertEquals("*/*", ShareFiles.commonTypeOf(emptyList()))
    }
}
