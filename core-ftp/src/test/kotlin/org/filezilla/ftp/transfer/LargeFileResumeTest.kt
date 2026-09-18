package org.filezilla.ftp.transfer

import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.protocol.Capability
import org.filezilla.ftp.protocol.CapabilityName
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * The 2 GB / 4 GB resume probe, against a server that honours `REST` and one
 * that pretends to.
 *
 * Some older servers truncate the resume offset to 32 bits, so a `REST` past
 * the boundary is acknowledged with 350 and then silently ignored -- the
 * transfer restarts from zero and the bytes land in the wrong place. FileZilla
 * does not trust the 350; it probes with `REST (size - 1)` + `RETR` and
 * requires exactly one byte back (`filetransfer.cpp:188-235`,
 * `transfersocket.cpp:353-384`). These tests exercise both outcomes.
 *
 * The files are sparse, so crossing the 2 GB boundary costs no real disk and
 * no real transfer: the probe moves one byte and the resumed transfer moves
 * only the tail.
 */
class LargeFileResumeTest {

    @TempDir
    lateinit var tempDir: Path

    private var server: FtpsTestServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun startServer(ignoreRest: Boolean): FtpsTestServer {
        assumeTrue(
            FtpsTestServer.isAvailable,
            "FTPS test server not set up; run core-ftp/src/test/resources/ftps-server/setup.sh",
        )
        return FtpsTestServer(ignoreRest = ignoreRest).also {
            it.start()
            server = it
        }
    }

    private fun <T> connected(
        s: FtpsTestServer,
        capabilities: ServerCapabilities,
        block: (FtpTransferEngine) -> T,
    ): T {
        val settings = FtpSettings(
            host = "127.0.0.1",
            port = s.port,
            user = s.user,
            password = s.password,
            security = FtpSecurity.EXPLICIT_TLS,
            trustAllCertificates = true,
        )
        return FtpControlConnection(settings, capabilities).use { control ->
            control.connect()
            control.login()
            block(FtpTransferEngine(control, capabilities))
        }
    }

    /** A sparse local partial file of exactly [length] bytes. */
    private fun sparseLocal(name: String, length: Long): File {
        val file = File(tempDir.toFile(), name)
        RandomAccessFile(file, "rw").use { it.setLength(length) }
        return file
    }

    @Test
    fun `a server that honours REST past 2GB passes the probe and resumes`() {
        val s = startServer(ignoreRest = false)
        val tail = 100L
        val remoteSize = TWO_GB + tail
        s.putSparseFile("big.bin", remoteSize)
        val target = sparseLocal("big.bin", TWO_GB)

        val capabilities = ServerCapabilities()
        val outcome = connected(s, capabilities) {
            it.download("big.bin", target.asTransferWriter())
        }

        assertEquals(TransferDisposition.RESUMED, outcome.disposition)
        assertEquals(TWO_GB, outcome.resumeOffset)
        assertEquals(tail, outcome.bytesTransferred)
        assertEquals(remoteSize, target.length())

        // The probe's verdict is cached, so it is not repeated for this server.
        assertEquals(
            Capability.NO,
            capabilities.get(settingsKey(s), CapabilityName.RESUME_2GB_BUG),
        )
    }

    @Test
    fun `a server that ignores REST past 2GB fails the probe and the transfer stops`() {
        val s = startServer(ignoreRest = true)
        s.putSparseFile("big.bin", TWO_GB + 100)
        val target = sparseLocal("big.bin", TWO_GB)

        val capabilities = ServerCapabilities()
        val failure = assertThrows<ResumeUnsupportedException> {
            connected(s, capabilities) { it.download("big.bin", target.asTransferWriter()) }
        }

        assertEquals(2, failure.limitGigabytes)
        // Refusing is the point: resuming here would have written the start of
        // the file over the middle of the local copy.
        assertEquals(TWO_GB, target.length())
        assertEquals(
            Capability.YES,
            capabilities.get(settingsKey(s), CapabilityName.RESUME_2GB_BUG),
        )
    }

    @Test
    fun `a cached verdict skips the probe on the next transfer`() {
        val s = startServer(ignoreRest = true)
        s.putSparseFile("big.bin", TWO_GB + 100)
        val target = sparseLocal("big.bin", TWO_GB)

        val capabilities = ServerCapabilities()
        // Pre-seed the verdict as if an earlier transfer had probed.
        capabilities.set(settingsKey(s), CapabilityName.RESUME_2GB_BUG, Capability.YES)

        val failure = assertThrows<ResumeUnsupportedException> {
            connected(s, capabilities) { it.download("big.bin", target.asTransferWriter()) }
        }
        assertEquals(2, failure.limitGigabytes)
    }

    @Test
    fun `a file below the boundary is resumed without probing at all`() {
        // Even a server with the offset bug resumes correctly below 2 GB, so
        // the probe must not fire and the transfer must go through.
        val s = startServer(ignoreRest = false)
        val size = 40_000
        val partial = 17_777
        s.putFile("small.bin", size)
        val target = File(tempDir.toFile(), "small.bin")
        target.writeBytes(FtpsTestServer.contentOf(size).copyOf(partial))

        val capabilities = ServerCapabilities()
        val outcome = connected(s, capabilities) {
            it.download("small.bin", target.asTransferWriter())
        }

        assertEquals(TransferDisposition.RESUMED, outcome.disposition)
        assertEquals(
            Capability.UNKNOWN,
            capabilities.get(settingsKey(s), CapabilityName.RESUME_2GB_BUG),
        )
    }

    private fun settingsKey(s: FtpsTestServer) =
        ServerCapabilities.ServerKey("127.0.0.1", s.port, s.user)

    private companion object {
        const val TWO_GB = 1L shl 31
    }
}
