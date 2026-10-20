package org.filezilla.ftp.webdav

import org.filezilla.ftp.io.asTransferReader
import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.testing.DockerServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.random.Random

/**
 * WebDAV transfers through the engine, proven against Apache mod_dav.
 *
 * The engine and its retry wrapper are what the app's queue drives, so the
 * round trip is checked here: send a file up through [WebDavResilientTransfer],
 * fetch it back and compare the bytes, and -- the one that earns the ranged GET
 * -- resume a download onto a partial file and get the whole thing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebDavTransferTest {

    private val server = DockerServer(
        name = "olo-test-webdav-xfer",
        image = "bytemark/webdav",
        ports = mapOf(18082 to 80),
        env = mapOf("USERNAME" to "olo", "PASSWORD" to "secret"),
    )

    private lateinit var settings: WebDavSettings

    @BeforeAll
    fun up() {
        assumeTrue(DockerServer.dockerAvailable(), "Docker is not available")
        server.start()
        settings = WebDavSettings(
            host = "127.0.0.1",
            port = server.hostPort(80),
            https = false,
            user = "olo",
            password = "secret",
            basePath = "/",
        )
        // Apache opens the port a moment before it answers HTTP; give the
        // first request a few tries.
        var lastError: Exception? = null
        repeat(20) {
            try {
                WebDavClient(settings).apply { connect(); makeCollection("/xfer") }
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw lastError ?: IllegalStateException("WebDAV server did not become ready")
    }

    @AfterAll
    fun down() {
        runCatching { server.stop() }
    }

    private fun transfer() = WebDavResilientTransfer(settings)

    @Test
    fun `a file sent up comes back down byte for byte`(@TempDir dir: Path) {
        val payload = Random(1).nextBytes(200_000)
        val local = File(dir.toFile(), "up.bin").apply { writeBytes(payload) }

        transfer().upload("/xfer/round.bin", resume = false, progress = null) { local.asTransferReader() }

        val back = File(dir.toFile(), "down.bin")
        transfer().download("/xfer/round.bin", forcedResumeOffset = null, progress = null) { back.asTransferWriter() }

        assertArrayEquals(payload, back.readBytes())
    }

    @Test
    fun `a download resumes onto a partial file`(@TempDir dir: Path) {
        val payload = Random(2).nextBytes(150_000)
        val local = File(dir.toFile(), "src.bin").apply { writeBytes(payload) }
        transfer().upload("/xfer/resume.bin", resume = false, progress = null) { local.asTransferReader() }

        // A partial download of the first 60 KB, exactly the bytes already there.
        val partial = File(dir.toFile(), "partial.bin").apply { writeBytes(payload.copyOfRange(0, 60_000)) }
        assertEquals(60_000L, partial.length())

        // The writer reports 60 KB already present, so the engine asks the
        // server only for the rest, from byte 60 000 on.
        transfer().download("/xfer/resume.bin", forcedResumeOffset = null, progress = null) {
            partial.asTransferWriter()
        }

        assertArrayEquals(payload, partial.readBytes())
    }
}
