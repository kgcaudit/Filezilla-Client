package org.filezilla.ftp.journal

import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.testing.FtpsTestServer
import org.filezilla.ftp.transfer.RetryPolicy
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
 * Picking a transfer back up from the journal, the way the app does after
 * Android kills it.
 *
 * Each "restart" here builds a brand-new [JournalledTransfer] with nothing in
 * memory except the journal, which is the situation a killed app wakes up in.
 */
class JournalledTransferTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: FtpsTestServer
    private val journal = InMemoryTransferJournal()

    @BeforeEach
    fun setUp() {
        assumeTrue(
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

    private fun settings() = FtpSettings(
        host = "127.0.0.1",
        port = server.port,
        user = server.user,
        password = server.password,
        security = FtpSecurity.EXPLICIT_TLS,
        trustAllCertificates = true,
        readTimeoutMillis = 3_000,
    )

    /** A fresh driver, as if the process had just started. */
    private fun newDriver() = JournalledTransfer(
        journal = journal,
        settings = settings(),
        retryPolicy = RetryPolicy(maxAttempts = 3),
        sleep = { },
        journalEveryBytes = 16 * 1024,
    )

    private fun record(remote: String, part: File) = TransferRecord(
        id = "job-1",
        direction = TransferDirection.DOWNLOAD,
        host = "127.0.0.1",
        port = server.port,
        user = server.user,
        remotePath = remote,
        localPath = part.absolutePath,
    )

    @Test
    fun `records progress as it goes and completes`() {
        val size = 300_000
        server.putFile("j.bin", size)
        val part = File(tempDir.toFile(), "j.bin.part")

        val done = newDriver().download(
            record = record("j.bin", part),
            currentRemote = RemoteFingerprint(size.toLong(), 1_700_000_000_000),
            localPartialSize = null,
            writerFactory = { part.asTransferWriter() },
        )

        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals(size.toLong(), done.bytesTransferred)
        assertArrayEquals(FtpsTestServer.contentOf(size), part.readBytes())

        // The journal holds the finished record, not a stale in-flight one.
        assertEquals(TransferState.COMPLETED, journal.get("job-1")!!.state)
    }

    @Test
    fun `a killed transfer is picked up from the journal and finishes correctly`() {
        val size = 400_000
        server.putFile("k.bin", size)
        val part = File(tempDir.toFile(), "k.bin.part")
        val fingerprint = RemoteFingerprint(size.toLong(), 1_700_000_000_000)

        // First run: stopped partway, standing in for the process being killed.
        runCatching {
            newDriver().download(
                record = record("k.bin", part),
                currentRemote = fingerprint,
                localPartialSize = null,
                writerFactory = { part.asTransferWriter() },
                progress = { transferred, resumeOffset, _ ->
                    if (resumeOffset + transferred >= 120_000) throw ProcessDied()
                },
            )
        }

        val afterKill = journal.get("job-1")!!
        assertEquals(TransferState.INTERRUPTED, afterKill.state)
        assertTrue(afterKill.bytesTransferred > 0, "the journal should have recorded progress")
        assertTrue(afterKill.bytesTransferred < size, "the transfer should not have finished")

        // Second run: a new driver with nothing but the journal to go on.
        val done = newDriver().download(
            record = afterKill,
            currentRemote = fingerprint,
            localPartialSize = part.length(),
            writerFactory = { part.asTransferWriter() },
        )

        assertEquals(TransferState.COMPLETED, done.state)
        assertTrue(done.attempts >= 2, "the resumed run should count as another attempt")
        assertArrayEquals(FtpsTestServer.contentOf(size), part.readBytes())
    }

    @Test
    fun `a remote file replaced while the app was dead is fetched again in full`() {
        val size = 200_000
        server.putFile("swap.bin", size)
        val part = File(tempDir.toFile(), "swap.bin.part")

        runCatching {
            newDriver().download(
                record = record("swap.bin", part),
                currentRemote = RemoteFingerprint(size.toLong(), 1_700_000_000_000),
                localPartialSize = null,
                writerFactory = { part.asTransferWriter() },
                progress = { transferred, resumeOffset, _ ->
                    if (resumeOffset + transferred >= 60_000) throw ProcessDied()
                },
            )
        }
        val afterKill = journal.get("job-1")!!
        assertTrue(afterKill.bytesTransferred > 0)

        // Someone replaces the file with different content of the same length.
        // Resuming would splice two files together and the result would look
        // fine, so this must start over.
        val replacement = ByteArray(size) { i -> ((i * 7 + 3) % 251).toByte() }
        File(server.root, "swap.bin").writeBytes(replacement)

        val done = newDriver().download(
            record = afterKill,
            currentRemote = RemoteFingerprint(size.toLong(), 1_900_000_000_000),
            localPartialSize = part.length(),
            writerFactory = { part.asTransferWriter() },
        )

        assertEquals(TransferState.COMPLETED, done.state)
        assertArrayEquals(replacement, part.readBytes())
    }

    @Test
    fun `an already complete transfer does nothing`() {
        val size = 50_000
        server.putFile("done.bin", size)
        val part = File(tempDir.toFile(), "done.bin.part")
        part.writeBytes(FtpsTestServer.contentOf(size))
        val fingerprint = RemoteFingerprint(size.toLong(), 1_700_000_000_000)

        val done = newDriver().download(
            record = record("done.bin", part).copy(
                bytesTransferred = size.toLong(),
                fingerprint = fingerprint,
            ),
            currentRemote = fingerprint,
            localPartialSize = part.length(),
            writerFactory = { error("should not open a writer for a finished transfer") },
        )

        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals(size.toLong(), done.bytesTransferred)
    }

    private class ProcessDied : RuntimeException("simulated process death")
}
