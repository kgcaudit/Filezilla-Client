package org.filezilla.android.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * That the image viewer recognises and rasterises SVG.
 *
 * SVG is vector XML, not a bitmap, so it never went through BitmapFactory with
 * the other formats; this covers the two halves of adding it -- the sniff that
 * spots one however it is named (a server preview arrives as a nameless scratch
 * file), and the render that turns one into a bitmap sized to the box asked
 * for, keeping its shape and never blowing past the pixel ceiling.
 */
@RunWith(RobolectricTestRunner::class)
class ImageFilesSvgTest {

    private val squareSvg =
        """<?xml version="1.0" encoding="UTF-8"?>
           <svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100">
             <rect width="100" height="100" fill="#3366cc"/>
           </svg>""".trimIndent()

    private val wideSvg =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 100">
             <rect width="400" height="100" fill="#cc3366"/>
           </svg>""".trimIndent()

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    // ----------------------------------------------------------- the sniff

    @Test
    fun `an svg is recognised by name and by content`() {
        assertTrue(ImageFiles.looksImage("logo.svg"))
        assertTrue(ImageFiles.isSvg(squareSvg.toByteArray()))
        // A declaration or a comment before the root is still an svg.
        assertTrue(ImageFiles.isSvg("<?xml version='1.0'?><!-- made by hand --><svg/>".toByteArray()))
    }

    @Test
    fun `a png is not mistaken for an svg`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        assertFalse(ImageFiles.isSvg(png))
        // An XML file that is not an svg is not one either.
        assertFalse(ImageFiles.isSvg("<?xml version='1.0'?><rss></rss>".toByteArray()))
    }

    @Test
    fun `the sniff reads a scratch file with no telling extension`() {
        val scratch = File.createTempFile("srvthumb-", ".tmp").also { it.deleteOnExit() }
        Files.write(scratch.toPath(), squareSvg.toByteArray())
        assertTrue(ImageFiles.isSvg(scratch))
    }

    @Test
    fun `a gzip-compressed svgz is recognised by name and by content`() {
        assertTrue(ImageFiles.looksImage("logo.svgz"))
        // The raw bytes are gzip, not text, so the sniff has to unzip first.
        assertTrue(ImageFiles.isSvg(gzip(squareSvg.toByteArray())))
        // Gzip of something that is not an svg is still not an svg.
        assertFalse(ImageFiles.isSvg(gzip("<?xml version='1.0'?><rss></rss>".toByteArray())))
    }

    // --------------------------------------------------------- the render

    @Test
    fun `a square svg fills a square box`() {
        val bitmap = ImageFiles.decode(squareSvg.toByteArray(), 256, 256)
        assertNotNull(bitmap)
        assertEquals(256, bitmap!!.width)
        assertEquals(256, bitmap.height)
    }

    @Test
    fun `a wide svg keeps its shape inside the box`() {
        // 4:1, fitted into a 400x400 box -> 400x100, not stretched to the box.
        val bitmap = ImageFiles.decode(wideSvg.toByteArray(), 400, 400)
        assertNotNull(bitmap)
        assertEquals(400, bitmap!!.width)
        assertEquals(100, bitmap.height)
    }

    @Test
    fun `a file-based decode renders too`() {
        val file = File.createTempFile("drawing-", ".svg").also { it.deleteOnExit() }
        Files.write(file.toPath(), squareSvg.toByteArray())
        val bitmap = ImageFiles.decode(file, 64, 64)
        assertNotNull(bitmap)
        assertEquals(64, bitmap!!.width)
    }

    @Test
    fun `a gzipped svgz renders from bytes and from a file`() {
        val gz = gzip(squareSvg.toByteArray())

        val fromBytes = ImageFiles.decode(gz, 128, 128)
        assertNotNull(fromBytes)
        assertEquals(128, fromBytes!!.width)
        assertEquals(128, fromBytes.height)

        val file = File.createTempFile("drawing-", ".svgz").also { it.deleteOnExit() }
        Files.write(file.toPath(), gz)
        val fromFile = ImageFiles.decode(file, 48, 48)
        assertNotNull(fromFile)
        assertEquals(48, fromFile!!.width)
    }
}
