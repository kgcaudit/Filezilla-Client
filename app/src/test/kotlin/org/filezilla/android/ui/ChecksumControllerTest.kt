package org.filezilla.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.filezilla.android.files.Checksums
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

/**
 * That the checksum controller sums the right bytes, re-sums on an algorithm
 * change, and forgets itself when closed.
 *
 * The hash itself is the point: a dialog that shows a plausible-looking but
 * wrong fingerprint is worse than none, since the whole use of it is to be
 * trusted against a published sum. So each case checks the controller's hash
 * against one computed here the plain way.
 */
@RunWith(RobolectricTestRunner::class)
class ChecksumControllerTest {

    private val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)

    @After
    fun tearDown() {
        scope.cancel()
        dispatcher.close()
        quiesceMainLooper()
    }

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("sum", ".bin").apply { writeBytes(bytes); deleteOnExit() }

    private fun expected(bytes: ByteArray, digest: String): String =
        MessageDigest.getInstance(digest).digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `sums a file with SHA-256 by default`() {
        val bytes = "hello checksum".toByteArray()
        val file = tempFile(bytes)
        val controller = ChecksumController(scope)

        controller.start(file)
        scope.drain()

        assertEquals(Checksums.Algorithm.SHA256, controller.state?.algorithm)
        assertEquals(file.name, controller.state?.name)
        assertEquals(expected(bytes, "SHA-256"), controller.state?.hash)
        assertEquals(1f, controller.state?.progress)
    }

    @Test
    fun `changing the algorithm re-sums with the new one`() {
        val bytes = "switch me".toByteArray()
        val file = tempFile(bytes)
        val controller = ChecksumController(scope)

        controller.start(file)
        scope.drain()

        controller.setAlgorithm(Checksums.Algorithm.MD5)
        scope.drain()

        assertEquals(Checksums.Algorithm.MD5, controller.state?.algorithm)
        assertEquals(expected(bytes, "MD5"), controller.state?.hash)
    }

    @Test
    fun `closing forgets the state`() {
        val file = tempFile("x".toByteArray())
        val controller = ChecksumController(scope)

        controller.start(file)
        scope.drain()

        controller.close()
        assertNull(controller.state)
    }
}
