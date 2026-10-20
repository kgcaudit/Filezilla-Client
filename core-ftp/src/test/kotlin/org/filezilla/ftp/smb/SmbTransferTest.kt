package org.filezilla.ftp.smb

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
 * SMB transfers through the engine, proven against Samba.
 *
 * The engine and its retry wrapper are what the app's queue drives, so the round
 * trip is checked here: send a file up through [SmbResilientTransfer], fetch it
 * back and compare the bytes; resume a download onto a partial local file; and
 * resume an upload onto a partial remote file -- the two SMB does that WebDAV
 * cannot. Skips when Docker is not available.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmbTransferTest {

    private val server = DockerServer(
        name = "olo-test-smb-xfer",
        image = "dperson/samba",
        ports = mapOf(11446 to 445),
        env = mapOf("USERID" to "0", "GROUPID" to "0"),
        args = listOf(
            "-p",
            "-u", "$USER;$PASS",
            "-s", "$SHARE;/share;yes;no;no;$USER",
            "-w", "WORKGROUP",
        ),
    )

    private lateinit var settings: SmbSettings

    @BeforeAll
    fun up() {
        assumeTrue(DockerServer.dockerAvailable(), "Docker is not available")
        server.start()
        settings = SmbSettings(
            host = "127.0.0.1",
            port = server.hostPort(445),
            user = USER,
            password = PASS,
            domain = "WORKGROUP",
            share = SHARE,
        )
        var lastError: Exception? = null
        repeat(30) {
            try {
                SmbClient(settings).use { it.connect(); it.makeDirectory("/xfer") }
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw lastError ?: IllegalStateException("the SMB share did not become ready")
    }

    @AfterAll
    fun down() {
        runCatching { server.stop() }
    }

    private fun transfer() = SmbResilientTransfer(settings)

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
        transfer().upload("/xfer/resume-down.bin", resume = false, progress = null) { local.asTransferReader() }

        val partial = File(dir.toFile(), "partial.bin").apply { writeBytes(payload.copyOfRange(0, 60_000)) }
        assertEquals(60_000L, partial.length())

        transfer().download("/xfer/resume-down.bin", forcedResumeOffset = null, progress = null) {
            partial.asTransferWriter()
        }

        assertArrayEquals(payload, partial.readBytes())
    }

    @Test
    fun `an upload resumes onto a partial remote file`(@TempDir dir: Path) {
        val payload = Random(3).nextBytes(150_000)
        val local = File(dir.toFile(), "whole.bin").apply { writeBytes(payload) }

        // Seed the remote with the first 70 KB, as a cut-short upload would leave.
        val head = File(dir.toFile(), "head.bin").apply { writeBytes(payload.copyOfRange(0, 70_000)) }
        transfer().upload("/xfer/resume-up.bin", resume = false, progress = null) { head.asTransferReader() }
        assertEquals(70_000L, SmbClient(settings).use { it.connect(); it.fingerprint("/xfer/resume-up.bin").size })

        // Now upload the whole file with resume: only the tail should be sent.
        transfer().upload("/xfer/resume-up.bin", resume = true, progress = null) { local.asTransferReader() }

        val back = File(dir.toFile(), "verify.bin")
        transfer().download("/xfer/resume-up.bin", forcedResumeOffset = null, progress = null) { back.asTransferWriter() }
        assertArrayEquals(payload, back.readBytes())
    }

    private companion object {
        const val USER = "olo"
        const val PASS = "secret"
        const val SHARE = "share"
    }
}
