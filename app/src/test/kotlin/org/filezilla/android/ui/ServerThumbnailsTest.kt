package org.filezilla.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.filezilla.android.data.SiteEntity
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.TransferMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The fetch-decode-cache pipeline, driven without a server.
 *
 * The fetch is a seam ([ServerThumbnails]'s `fetch`), so the test hands one
 * that writes a picture straight into the scratch file and counts how often it
 * is called. That is what lets the parts this class actually owns -- deciding
 * whether to fetch at all, caching a hit, not caching a miss, respecting the
 * switch and the Wi-Fi rule -- be checked as behaviour rather than asserted
 * about in the abstract.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerThumbnailsTest {

    private val site = SiteEntity(
        id = "1",
        name = "NAS",
        host = "nas.example.org",
        port = 21,
        user = "bob",
        passwordCipher = "",
        security = FtpSecurity.PLAIN.name,
        transferMode = TransferMode.DEFAULT.name,
        initialPath = null,
    )

    private fun freshCacheDir(): File =
        File(System.getProperty("java.io.tmpdir"), "srvthumb-${UUID.randomUUID()}")

    /** Writes a real (tiny) PNG into [into], so the decode path has something to read. */
    private fun writePicture(into: File) {
        into.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        into.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun thumbs(
        enabled: Boolean = true,
        allowed: Boolean = true,
        fetch: (File) -> Unit,
    ): Pair<ServerThumbnails, () -> Int> {
        var count = 0
        val instance = ServerThumbnails(
            cacheDir = freshCacheDir(),
            enabled = { enabled },
            allowedNow = { allowed },
            fetch = { _, _, into, _ -> count++; fetch(into) },
        )
        return instance to { count }
    }

    @Test
    fun `an image is fetched, decoded and then served from the cache`() = runBlocking {
        val (thumbs, fetches) = thumbs { writePicture(it) }
        val first = thumbs.load(site, "/photos/a.jpg", "100", 1_000L, FileKind.IMAGE, 64)
        val second = thumbs.load(site, "/photos/a.jpg", "100", 1_000L, FileKind.IMAGE, 64)
        assertNotNull("first load decodes a bitmap", first)
        assertNotNull("second load returns the cached bitmap", second)
        assertEquals("the second look does not fetch again", 1, fetches())
    }

    @Test
    fun `a file over the cap is never fetched`() = runBlocking {
        val (thumbs, fetches) = thumbs { writePicture(it) }
        val result = thumbs.load(
            site, "/photos/huge.jpg", "1", ServerThumbnails.MAX_FETCH_BYTES + 1, FileKind.IMAGE, 64,
        )
        assertNull(result)
        assertEquals(0, fetches())
    }

    @Test
    fun `a document is never fetched`() = runBlocking {
        val (thumbs, fetches) = thumbs { writePicture(it) }
        val result = thumbs.load(site, "/docs/a.pdf", "1", 1_000L, FileKind.DOCUMENT, 64)
        assertNull(result)
        assertEquals(0, fetches())
    }

    @Test
    fun `nothing is fetched when thumbnails are switched off`() = runBlocking {
        val (thumbs, fetches) = thumbs(enabled = false) { writePicture(it) }
        val result = thumbs.load(site, "/photos/a.jpg", "1", 1_000L, FileKind.IMAGE, 64)
        assertNull(result)
        assertEquals(0, fetches())
    }

    @Test
    fun `nothing is fetched when the connection is not allowed`() = runBlocking {
        val (thumbs, fetches) = thumbs(allowed = false) { writePicture(it) }
        val result = thumbs.load(site, "/photos/a.jpg", "1", 1_000L, FileKind.IMAGE, 64)
        assertNull(result)
        assertEquals(0, fetches())
    }

    @Test
    fun `a failed fetch is not cached, so a later look tries again`() = runBlocking {
        val (thumbs, fetches) = thumbs { throw IOException("connection reset") }
        assertNull(thumbs.load(site, "/photos/a.jpg", "1", 1_000L, FileKind.IMAGE, 64))
        assertNull(thumbs.load(site, "/photos/a.jpg", "1", 1_000L, FileKind.IMAGE, 64))
        assertEquals("a failure leaves nothing cached, so the second look fetches", 2, fetches())
    }

    @Test
    fun `a video is fetched only up to its prefix`() = runBlocking {
        var capturedMax: Long? = -1
        val thumbs = ServerThumbnails(
            cacheDir = freshCacheDir(),
            enabled = { true },
            allowedNow = { true },
            fetch = { _, _, into, maxBytes ->
                capturedMax = maxBytes
                into.parentFile?.mkdirs()
                into.writeText("truncated clip")
            },
        )
        // A huge clip is eligible because only a prefix is fetched; decoding a
        // truncated junk file yields nothing, but the bound is the point here.
        thumbs.load(site, "/videos/clip.mp4", "1", 2_000_000_000L, FileKind.VIDEO, 64)
        assertEquals(ServerThumbnails.VIDEO_PREFIX_BYTES, capturedMax)
    }

    @Test
    fun `a picture is fetched whole, with no prefix bound`() = runBlocking {
        var capturedMax: Long? = -1
        val thumbs = ServerThumbnails(
            cacheDir = freshCacheDir(),
            enabled = { true },
            allowedNow = { true },
            fetch = { _, _, into, maxBytes -> capturedMax = maxBytes; writePicture(into) },
        )
        val result = thumbs.load(site, "/photos/a.jpg", "1", 1_000L, FileKind.IMAGE, 64)
        assertNotNull(result)
        assertNull("a picture is fetched whole", capturedMax)
    }

    @Test
    fun `a fetched file with no picture is remembered as a miss`() = runBlocking {
        // A fetch that writes junk: it is fetched once, decodes to nothing, and
        // that nothing is cached so a second look does not fetch it again.
        val (thumbs, fetches) = thumbs { it.parentFile?.mkdirs(); it.writeText("not an image") }
        assertNull(thumbs.load(site, "/photos/broken.jpg", "1", 1_000L, FileKind.IMAGE, 64))
        assertNull(thumbs.load(site, "/photos/broken.jpg", "1", 1_000L, FileKind.IMAGE, 64))
        assertEquals("a decoded miss is cached", 1, fetches())
    }
}
