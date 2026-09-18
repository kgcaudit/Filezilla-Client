package org.filezilla.ftp.transfer

import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.protocol.TransferMode
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Active mode: the server connects back to us instead of the other way round.
 *
 * `FtpSettings.transferMode` existed from the start and nothing read it --
 * every data connection was passive whatever it said. These tests exist
 * because the fix is protocol work: `PORT` before the transfer command, the
 * accept only after it, and the TLS handshake on a socket that arrived rather
 * than one that was dialled.
 */
class ActiveModeIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: FtpsTestServer

    @BeforeEach
    fun setUp() {
        assumeTrue(FtpsTestServer.isAvailable, "FTPS test server not set up; run setup.sh")
        server = FtpsTestServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private fun <T> connected(mode: TransferMode, block: (FtpTransferEngine) -> T): T {
        val capabilities = ServerCapabilities()
        val settings = FtpSettings(
            host = "127.0.0.1",
            port = server.port,
            user = server.user,
            password = server.password,
            security = FtpSecurity.EXPLICIT_TLS,
            trustAllCertificates = true,
            transferMode = mode,
        )
        return FtpControlConnection(settings, capabilities).use { control ->
            control.connect()
            control.login()
            block(FtpTransferEngine(control, capabilities))
        }
    }

    @Test
    fun `a directory listing arrives over an active data connection`() {
        server.putFile("one.bin", 10)
        server.putFile("two.bin", 20)

        val entries = connected(TransferMode.ACTIVE) { it.list() }

        assertEquals(setOf("one.bin", "two.bin"), entries.map { it.name }.toSet())
    }

    @Test
    fun `a download over an active connection is byte for byte correct`() {
        val source = FtpsTestServer.contentOf(120_000)
        server.putFile("payload.bin", 120_000)
        val target = File(tempDir.toFile(), "payload.bin")

        val outcome = connected(TransferMode.ACTIVE) {
            it.download("payload.bin", target.asTransferWriter())
        }

        assertEquals(TransferDisposition.TRANSFERRED, outcome.disposition)
        assertArrayEquals(source, target.readBytes())
    }

    @Test
    fun `resume works in active mode too`() {
        // The ordering active mode changes -- PORT, then REST, then RETR, then
        // accept -- is exactly where a resumed transfer could go wrong, so the
        // offset is checked here and not only in the passive tests.
        val source = FtpsTestServer.contentOf(80_000)
        server.putFile("resume.bin", 80_000)
        val target = File(tempDir.toFile(), "resume.bin")
        target.writeBytes(source.copyOfRange(0, 30_000))

        val outcome = connected(TransferMode.ACTIVE) {
            it.download("resume.bin", target.asTransferWriter())
        }

        assertEquals(TransferDisposition.RESUMED, outcome.disposition)
        assertEquals(30_000L, outcome.resumeOffset)
        assertArrayEquals(source, target.readBytes())
    }

    @Test
    fun `passive stays the default when nothing asks for active`() {
        server.putFile("one.bin", 10)
        val entries = connected(TransferMode.DEFAULT) { it.list() }
        assertTrue(entries.any { it.name == "one.bin" })
    }
}
