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
    fun `a normal page is not shrunk by the safety cap`() {
        // 1400x2000 is under both the target-cover rule and the hard cap.
        assertEquals(1, ImageFiles.sampleSize(1400, 2000, 1080, 2200))
    }

    @Test
    fun `a very tall webtoon strip is shrunk enough to hold and to draw`() {
        // The crash: 2070x19337 is barely wider than a phone, so covering the
        // screen never shrank it, and its full-size bitmap was too big to draw.
        val sample = ImageFiles.sampleSize(2070, 19337, 1080, 2200)
        assertTrue("it must be shrunk at all", sample >= 8)
        assertTrue("no side past the texture limit", 2070 / sample <= 4096 && 19337 / sample <= 4096)
        assertTrue("within the pixel cap", (2070L / sample) * (19337L / sample) <= 8_000_000L)
    }

    @Test
    fun `the cap applies even with no target`() {
        // 4000x3000 is 12MP, past the cap, so it is shrunk even unmeasured.
        assertEquals(2, ImageFiles.sampleSize(4000, 3000, 0, 0))
    }

    @Test
    fun `which files the image viewer shows`() {
        assertTrue(ImageFiles.looksImage("photo.JPG"))
        assertTrue(ImageFiles.looksImage("page.webp"))
        assertFalse(ImageFiles.looksImage("clip.mp4"))
        assertFalse(ImageFiles.looksImage("noext"))
    }
}
