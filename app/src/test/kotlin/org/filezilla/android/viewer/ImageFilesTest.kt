package org.filezilla.android.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shrink an image is decoded at: enough to fit the screen without
 * carrying a camera's full resolution into memory to do it.
 */
class ImageFilesTest {

    @Test
    fun `a big photo is halved until it fits`() {
        // 4000x3000 into a 1000x1000 space: /2 gives 2000x1500, which still
        // covers; /4 would be 1000x750, and 750 no longer covers 1000, so /2
        // is the largest shrink that still fills the space.
        assertEquals(2, ImageFiles.sampleSize(4000, 3000, 1000, 1000))
    }

    @Test
    fun `an image already smaller than the target is not shrunk`() {
        assertEquals(1, ImageFiles.sampleSize(800, 600, 1000, 1000))
    }

    @Test
    fun `no target means full size`() {
        assertEquals(1, ImageFiles.sampleSize(4000, 3000, 0, 0))
    }

    @Test
    fun `which files the image viewer shows`() {
        assertTrue(ImageFiles.looksImage("photo.JPG"))
        assertTrue(ImageFiles.looksImage("page.webp"))
        assertFalse(ImageFiles.looksImage("clip.mp4"))
        assertFalse(ImageFiles.looksImage("noext"))
    }
}
