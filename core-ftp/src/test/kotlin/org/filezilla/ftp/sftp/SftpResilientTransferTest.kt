package org.filezilla.ftp.sftp

import org.filezilla.ftp.io.asTransferReader
import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.transfer.RetryPolicy
import org.filezilla.ftp.transfer.TransferAbort
import org.filezilla.ftp.transfer.TransferAbortedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

/**
 * The resilient SFTP transfer -- resume, progress and stop -- against a live
 * SSH server.
 *
 * The SFTP counterpart of the app's FTP resume tests, kept here because it is
 * plain JVM: MINA SSHD does not sit well on the app's Robolectric test
 * classpath, and this layer needs neither Android nor Robolectric. Retry
 * timing itself is [RetryPolicy]'s to test; this checks that a transfer
 * completes, resumes from an offset, reports progress, and stops when asked.
 */
class SftpResilientTransferTest {

    private lateinit var server: SftpTestServer

    @BeforeEach
    fun setUp() {
        server = SftpTestServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private fun transfer(abort: TransferAbort? = null) = SftpResilientTransfer(
        settings = server.settings(knownHostKey = server.discoverHostKey()),
        retryPolicy = RetryPolicy(maxAttempts = 3),
        sleep = {},
        abort = abort,
    )

    @Test
    fun `downloads a whole file`() {
        val source = server.putFile("movie.bin", 40_000)
        val into = File(Files.createTempDirectory("dl").toFile(), "movie.bin")

        val outcome = transfer().download("movie.bin", forcedResumeOffset = null, progress = null) {
            into.asTransferWriter()
        }

        assertEquals(40_000, outcome.outcome.bytesTransferred)
        assertArrayEquals(source.readBytes(), into.readBytes())
    }

    @Test
    fun `resumes a download from a pinned offset`() {
        val source = server.putFile("big.bin", 50_000)
        val into = File(Files.createTempDirectory("dl").toFile(), "big.bin")
        into.writeBytes(source.readBytes().copyOfRange(0, 20_000))

        val outcome = transfer().download("big.bin", forcedResumeOffset = 20_000, progress = null) {
            into.asTransferWriter()
        }

        assertEquals(20_000, outcome.outcome.resumeOffset)
        assertEquals(30_000, outcome.outcome.bytesTransferred)
        assertArrayEquals(source.readBytes(), into.readBytes())
    }

    @Test
    fun `uploads a whole file`() {
        val local = File(Files.createTempDirectory("up").toFile(), "photo.bin")
        local.writeBytes(ByteArray(24_000) { (it % 97).toByte() })

        transfer().upload("out/photo.bin", resume = false, progress = null) { local.asTransferReader() }

        assertArrayEquals(local.readBytes(), File(server.root, "out/photo.bin").readBytes())
    }

    @Test
    fun `resumes an upload onto a partial remote file`() {
        val content = ByteArray(30_000) { (it % 131).toByte() }
        val local = File(Files.createTempDirectory("up").toFile(), "doc.bin")
        local.writeBytes(content)
        File(server.root, "doc.bin").writeBytes(content.copyOfRange(0, 10_000))

        val outcome = transfer().upload("doc.bin", resume = true, progress = null) { local.asTransferReader() }

        assertEquals(10_000, outcome.outcome.resumeOffset)
        assertArrayEquals(content, File(server.root, "doc.bin").readBytes())
    }

    @Test
    fun `reports progress to the end`() {
        server.putFile("stream.bin", 80_000)
        val into = File(Files.createTempDirectory("dl").toFile(), "stream.bin")
        var lastSeen = 0L

        transfer().download(
            "stream.bin",
            forcedResumeOffset = null,
            progress = { transferred, _, _ -> lastSeen = transferred },
        ) { into.asTransferWriter() }

        assertEquals(80_000, lastSeen)
    }

    @Test
    fun `a stop requested before it starts ends the transfer`() {
        server.putFile("halt.bin", 10_000)
        val into = File(Files.createTempDirectory("dl").toFile(), "halt.bin")
        val abort = TransferAbort().apply { abortAndStop() }

        assertThrows<TransferAbortedException> {
            transfer(abort).download("halt.bin", forcedResumeOffset = null, progress = null) {
                into.asTransferWriter()
            }
        }
    }
}
