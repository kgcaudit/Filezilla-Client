package org.filezilla.ftp.transfer

import org.filezilla.ftp.io.asTransferReader
import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * End-to-end resume behaviour against a live FTPS server that enforces
 * `require_ssl_reuse`, the way vsftpd does by default.
 */
class ResumeIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: FtpsTestServer

    @BeforeEach
    fun setUp() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            FtpsTestServer.isAvailable,
            "FTPS test server not set up; run core-ftp/src/test/resources/ftps-server/setup.sh",
        )
        server = FtpsTestServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private fun <T> connected(block: (FtpTransferEngine) -> T): T {
        val capabilities = ServerCapabilities()
        val settings = FtpSettings(
            host = "127.0.0.1",
            port = server.port,
            user = server.user,
            password = server.password,
            security = FtpSecurity.EXPLICIT_TLS,
            pinnedCertificate = FtpsTestServer.fingerprint,
        )
        return FtpControlConnection(settings, capabilities).use { control ->
            control.connect()
            control.login()
            block(FtpTransferEngine(control, capabilities))
        }
    }

    // ------------------------------------------------------------- downloads

    @Test
    fun `downloads a whole file over a session-reusing data channel`() {
        val size = 300_000
        server.putFile("whole.bin", size)
        val target = File(tempDir.toFile(), "whole.bin")

        val outcome = connected { it.download("whole.bin", target.asTransferWriter()) }

        assertEquals(TransferDisposition.TRANSFERRED, outcome.disposition)
        assertEquals(0L, outcome.resumeOffset)
        assertEquals(size.toLong(), outcome.bytesTransferred)
        assertArrayEquals(FtpsTestServer.contentOf(size), target.readBytes())
    }

    @Test
    fun `resumes a partial download from exactly the right offset`() {
        val size = 300_000
        val partial = 123_457   // deliberately not a buffer multiple
        server.putFile("resume.bin", size)

        val target = File(tempDir.toFile(), "resume.bin")
        target.writeBytes(FtpsTestServer.contentOf(size).copyOf(partial))

        val outcome = connected { it.download("resume.bin", target.asTransferWriter()) }

        assertEquals(TransferDisposition.RESUMED, outcome.disposition)
        assertEquals(partial.toLong(), outcome.resumeOffset)
        assertEquals((size - partial).toLong(), outcome.bytesTransferred)
        // The whole file must be byte-identical, which is what catches an
        // off-by-one in the REST offset.
        assertArrayEquals(FtpsTestServer.contentOf(size), target.readBytes())
    }

    @Test
    fun `does not re-download a file that is already complete`() {
        val size = 50_000
        server.putFile("done.bin", size)
        val target = File(tempDir.toFile(), "done.bin")
        target.writeBytes(FtpsTestServer.contentOf(size))

        val outcome = connected { it.download("done.bin", target.asTransferWriter()) }

        assertEquals(TransferDisposition.ALREADY_COMPLETE, outcome.disposition)
        assertEquals(0L, outcome.bytesTransferred)
    }

    @Test
    fun `restarts when the local partial file is longer than the remote file`() {
        // The remote file changed under us, so the partial cannot be a prefix.
        val size = 40_000
        server.putFile("changed.bin", size)
        val target = File(tempDir.toFile(), "changed.bin")
        target.writeBytes(ByteArray(size + 5_000) { 0xAB.toByte() })

        val outcome = connected { it.download("changed.bin", target.asTransferWriter()) }

        assertEquals(TransferDisposition.TRANSFERRED, outcome.disposition)
        assertEquals(0L, outcome.resumeOffset)
        assertArrayEquals(FtpsTestServer.contentOf(size), target.readBytes())
    }

    @Test
    fun `a resumed download survives being interrupted repeatedly`() {
        val size = 400_000
        server.putFile("flaky.bin", size)
        val target = File(tempDir.toFile(), "flaky.bin")

        // Three interrupted attempts, each aborting at an absolute position in
        // the file, then a final run to completion. This is the mobile case:
        // the transfer is cut off again and again and must still converge on
        // the right bytes.
        for (stopAt in listOf(70_000L, 150_000L, 260_000L)) {
            runCatching {
                connected { engine ->
                    engine.download(
                        remoteFile = "flaky.bin",
                        writer = target.asTransferWriter(),
                        progress = { transferred, resumeOffset, _ ->
                            if (resumeOffset + transferred >= stopAt) throw InterruptedTransfer()
                        },
                    )
                }
            }
            assertTrue(target.length() >= stopAt, "attempt stopping at $stopAt got to ${target.length()}")
            assertTrue(target.length() < size, "attempt stopping at $stopAt ran to completion")
        }

        val outcome = connected { it.download("flaky.bin", target.asTransferWriter()) }

        assertEquals(TransferDisposition.RESUMED, outcome.disposition)
        assertArrayEquals(FtpsTestServer.contentOf(size), target.readBytes())
    }

    // --------------------------------------------------------------- uploads

    @Test
    fun `uploads a whole file`() {
        val size = 200_000
        val source = File(tempDir.toFile(), "up.bin")
        source.writeBytes(FtpsTestServer.contentOf(size))

        val outcome = connected { it.upload("up.bin", source.asTransferReader()) }

        assertEquals(TransferDisposition.TRANSFERRED, outcome.disposition)
        assertArrayEquals(FtpsTestServer.contentOf(size), File(server.root, "up.bin").readBytes())
    }

    @Test
    fun `resumes a partial upload`() {
        val size = 200_000
        val alreadyThere = 88_887
        val source = File(tempDir.toFile(), "upresume.bin")
        source.writeBytes(FtpsTestServer.contentOf(size))

        File(server.root, "upresume.bin")
            .writeBytes(FtpsTestServer.contentOf(size).copyOf(alreadyThere))

        val outcome = connected { it.upload("upresume.bin", source.asTransferReader()) }

        assertEquals(TransferDisposition.RESUMED, outcome.disposition)
        assertEquals(alreadyThere.toLong(), outcome.resumeOffset)
        assertEquals((size - alreadyThere).toLong(), outcome.bytesTransferred)
        assertArrayEquals(
            FtpsTestServer.contentOf(size),
            File(server.root, "upresume.bin").readBytes(),
        )
    }

    @Test
    fun `does not re-upload a file that is already complete`() {
        val size = 30_000
        val source = File(tempDir.toFile(), "updone.bin")
        source.writeBytes(FtpsTestServer.contentOf(size))
        File(server.root, "updone.bin").writeBytes(FtpsTestServer.contentOf(size))

        val outcome = connected { it.upload("updone.bin", source.asTransferReader()) }

        assertEquals(TransferDisposition.ALREADY_COMPLETE, outcome.disposition)
        assertEquals(0L, outcome.bytesTransferred)
    }

    private class InterruptedTransfer : RuntimeException("simulated interruption")
}
