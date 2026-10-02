package org.filezilla.android.files

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * That the photo info is read back the way it was written, and that the three
 * edits do exactly what they say -- turn without resetting, clear only the
 * location, or clear the lot while leaving the photo standing up.
 *
 * The turn table and the format gate are pure and checked on their own; the
 * read and the strips are driven against a real JPEG so a wrong tag name or a
 * save that drops the wrong thing fails here rather than on someone's photo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageExifTest {

    // --------------------------------------------------------- the turn table

    @Test
    fun `a clockwise turn walks the orientation round`() {
        var o = ExifInterface.ORIENTATION_NORMAL
        o = ImageExif.rotatedOrientation(o, clockwise = true); assertEquals(ExifInterface.ORIENTATION_ROTATE_90, o)
        o = ImageExif.rotatedOrientation(o, clockwise = true); assertEquals(ExifInterface.ORIENTATION_ROTATE_180, o)
        o = ImageExif.rotatedOrientation(o, clockwise = true); assertEquals(ExifInterface.ORIENTATION_ROTATE_270, o)
        o = ImageExif.rotatedOrientation(o, clockwise = true); assertEquals(ExifInterface.ORIENTATION_NORMAL, o)
    }

    @Test
    fun `counter-clockwise is the other way`() {
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, ImageExif.rotatedOrientation(ExifInterface.ORIENTATION_NORMAL, clockwise = false))
        // Four turns either way come home.
        var o = ExifInterface.ORIENTATION_NORMAL
        repeat(4) { o = ImageExif.rotatedOrientation(o, clockwise = false) }
        assertEquals(ExifInterface.ORIENTATION_NORMAL, o)
    }

    @Test
    fun `a mirrored photo keeps its mirror through a turn`() {
        // FLIP_HORIZONTAL turned clockwise stays mirrored, not reset to upright.
        val turned = ImageExif.rotatedOrientation(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, clockwise = true)
        assertEquals(ExifInterface.ORIENTATION_TRANSVERSE, turned)
    }

    // --------------------------------------------------------- the format gate

    @Test
    fun `only the writable formats can be edited`() {
        for (ok in listOf("a.jpg", "a.JPEG", "b.png", "c.webp")) assertTrue(ok, ImageExif.canEdit(ok))
        for (no in listOf("a.heic", "a.dng", "a.cr2", "a.gif", "noext")) assertFalse(no, ImageExif.canEdit(no))
    }

    // ------------------------------------------------------ the real round trip

    private fun jpegWith(block: (ExifInterface) -> Unit): File {
        val file = Files.createTempFile("exif", ".jpg").toFile()
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val exif = ExifInterface(file.absolutePath)
        block(exif)
        exif.saveAttributes()
        return file
    }

    @Test
    fun `the facts are read back as written`() {
        val file = jpegWith { exif ->
            exif.setAttribute(ExifInterface.TAG_MAKE, "Samsung")
            exif.setAttribute(ExifInterface.TAG_MODEL, "SM-S928N")
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:28 17:31:00")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            exif.setLatLong(37.5665, 126.9780)
        }
        val facts = ImageExif.read(file)
        assertEquals("Samsung", facts.cameraMake)
        assertEquals("SM-S928N", facts.cameraModel)
        assertNotNull(facts.dateTaken)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, facts.orientation)
        assertTrue(facts.hasLocation)
        assertEquals(37.5665, facts.latitude!!, 0.001)
        assertEquals(126.9780, facts.longitude!!, 0.001)
    }

    @Test
    fun `clearing the location leaves everything else`() {
        val file = jpegWith { exif ->
            exif.setAttribute(ExifInterface.TAG_MAKE, "Canon")
            exif.setLatLong(1.0, 2.0)
        }
        assertTrue(ImageExif.clearLocation(file))
        val facts = ImageExif.read(file)
        assertFalse("the location is gone", facts.hasLocation)
        assertEquals("the camera is not", "Canon", facts.cameraMake)
    }

    @Test
    fun `clearing all strips the camera and location but keeps the photo upright`() {
        val file = jpegWith { exif ->
            exif.setAttribute(ExifInterface.TAG_MAKE, "Nikon")
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:01 00:00:00")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString())
            exif.setLatLong(1.0, 2.0)
        }
        assertTrue(ImageExif.clearAll(file))
        val facts = ImageExif.read(file)
        assertNull(facts.cameraMake)
        assertNull(facts.dateTaken)
        assertFalse(facts.hasLocation)
        // Orientation is deliberately preserved, so the photo does not fall on
        // its side in a viewer that had been standing it up by the tag.
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, facts.orientation)
    }

    @Test
    fun `a rotate rewrites only the orientation`() {
        val file = jpegWith { exif ->
            exif.setAttribute(ExifInterface.TAG_MAKE, "Sony")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        }
        assertTrue(ImageExif.rotate(file, clockwise = true))
        val facts = ImageExif.read(file)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, facts.orientation)
        assertEquals("the rest is untouched", "Sony", facts.cameraMake)
    }
}
