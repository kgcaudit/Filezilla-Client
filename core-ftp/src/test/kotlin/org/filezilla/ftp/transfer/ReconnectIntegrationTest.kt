package org.filezilla.ftp.transfer

import org.filezilla.ftp.io.asTransferReader
import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Recovery from a connection that dies mid-transfer.
 *
 * The server cuts the data connection partway through, which is what a phone
 * sees when it changes network or loses signal. Nothing about the recovery is
 * simulated on the client side: the socket really goes away, and the engine
 * has to notice, reconnect, work out where the partial file ends and carry on
 * from there.
 */
class ReconnectIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private var server: FtpsTestServer? = null

    // Backoff is not what these tests are checking, so it is not waited out.
    private val noSleep: (Long) -> Unit = { }

    @AfterEach
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun startServer(dropAfterBytes: Int, dropTimes: Int): FtpsTestServer {
        assumeTrue(
            FtpsTestServer.isAvailable,
            "FTPS test server not set up; run core-ftp/src/test/resources/ftps-server/setup.sh",
        )
        return FtpsTestServer(dropAfterBytes = dropAfterBytes, dropTimes = dropTimes).also {
            it.start()
            server = it
        }
    }

    private fun transferFor(s: FtpsTestServer, retryPolicy: RetryPolicy = RetryPolicy()) =
        ResilientTransfer(
            settings = FtpSettings(
                host = "127.0.0.1",
                port = s.port,
                user = s.user,
                password = s.password,
                security = FtpSecurity.EXPLICIT_TLS,
                trustAllCertificates = true,
                // A cut transfer is detected by the control connection going
                // silent, so each drop costs one timeout. Shortened here to
                // keep the suite quick; the product default is 20 seconds.
                readTimeoutMillis = 3_000,
            ),
            retryPolicy = retryPolicy,
            sleep = noSleep,
        )

    @Test
    fun `recovers from a connection cut once and finishes the download`() {
        val size = 400_000
        val cutAfter = 90_000
        val s = startServer(dropAfterBytes = cutAfter, dropTimes = 1)
        s.putFile("cut.bin", size)
        val target = File(tempDir.toFile(), "cut.bin")

        val result = transferFor(s).download("cut.bin") { target.asTransferWriter() }

        assertEquals(2, result.attempts, "expected one failure then one success")
        assertEquals(TransferDisposition.RESUMED, result.outcome.disposition)
        // The second attempt picked up where the first died, so it moved only
        // what was left rather than starting over.
        assertTrue(
            result.outcome.resumeOffset in 1 until size.toLong(),
            "resumed from ${result.outcome.resumeOffset}, expected somewhere inside the file",
        )
        assertEquals(size.toLong() - result.outcome.resumeOffset, result.outcome.bytesTransferred)
        // The only assertion that really matters: the file is correct.
        assertArrayEquals(FtpsTestServer.contentOf(size), target.readBytes())
    }

    @Test
    fun `recovers from being cut three times in a row`() {
        val size = 400_000
        val s = startServer(dropAfterBytes = 60_000, dropTimes = 3)
        s.putFile("flaky.bin", size)
        val target = File(tempDir.toFile(), "flaky.bin")

        val result = transferFor(s).download("flaky.bin") { target.asTransferWriter() }

        assertEquals(4, result.attempts)
        assertArrayEquals(FtpsTestServer.contentOf(size), target.readBytes())
    }

    @Test
    fun `each attempt moves less than the last because progress is kept`() {
        // If a failed attempt threw away its work, every retry would move the
        // same amount and the transfer would never converge.
        val size = 300_000
        val s = startServer(dropAfterBytes = 50_000, dropTimes = 2)
        s.putFile("progress.bin", size)
        val target = File(tempDir.toFile(), "progress.bin")

        val offsets = mutableListOf<Long>()
        val result = transferFor(s).download(
            remoteFile = "progress.bin",
            progress = { _, resumeOffset, _ ->
                if (offsets.lastOrNull() != resumeOffset) offsets += resumeOffset
            },
        ) { target.asTransferWriter() }

        assertEquals(3, result.attempts)
        assertEquals(offsets.sorted(), offsets, "resume offsets should only ever move forward: $offsets")
        assertEquals(0L, offsets.first(), "the first attempt should start from the beginning")
        assertTrue(offsets.last() > 0, "later attempts should resume, not restart: $offsets")
        assertArrayEquals(FtpsTestServer.contentOf(size), target.readBytes())
    }

    @Test
    fun `gives up once the attempt budget is spent`() {
        val size = 400_000
        // Cut more times than the policy will tolerate.
        val s = startServer(dropAfterBytes = 40_000, dropTimes = 20)
        s.putFile("hopeless.bin", size)
        val target = File(tempDir.toFile(), "hopeless.bin")

        assertThrows<Exception> {
            transferFor(s, RetryPolicy(maxAttempts = 3)).download("hopeless.bin") {
                target.asTransferWriter()
            }
        }
        // Even having given up, what was received is kept and is a valid
        // prefix, so a later attempt can still resume rather than start over.
        val received = target.readBytes()
        assertTrue(received.isNotEmpty(), "partial file should survive a failed transfer")
        assertTrue(received.size < size, "partial file should be incomplete")
        assertArrayEquals(FtpsTestServer.contentOf(size).copyOf(received.size), received)
    }

    @Test
    fun `recovers a cut upload`() {
        val size = 250_000
        val s = startServer(dropAfterBytes = 0, dropTimes = 0)
        val source = File(tempDir.toFile(), "up.bin")
        source.writeBytes(FtpsTestServer.contentOf(size))

        // Uploads are cut by the client side of the data connection, so a
        // partial remote file is staged directly to set the same situation up.
        File(s.root, "up.bin").writeBytes(FtpsTestServer.contentOf(size).copyOf(70_000))

        val result = transferFor(s).upload("up.bin") { source.asTransferReader() }

        assertEquals(1, result.attempts)
        assertEquals(TransferDisposition.RESUMED, result.outcome.disposition)
        assertEquals(70_000L, result.outcome.resumeOffset)
        assertArrayEquals(FtpsTestServer.contentOf(size), File(s.root, "up.bin").readBytes())
    }

    @Test
    fun `a retry costs no extra bytes because the interrupted work is kept`() {
        val size = 300_000
        val s = startServer(dropAfterBytes = 80_000, dropTimes = 1)
        s.putFile("cost.bin", size)
        val target = File(tempDir.toFile(), "cost.bin")

        val result = transferFor(s).download("cost.bin") { target.asTransferWriter() }

        assertEquals(2, result.attempts)
        // This is the whole point of resuming rather than restarting: across
        // both attempts the connection carried the file exactly once. A client
        // that restarted on failure would have moved more than `size` here,
        // and on a metered mobile connection the user pays that difference.
        assertEquals(
            size.toLong(),
            result.bytesAcrossAttempts,
            "a resumed transfer should move each byte once",
        )
        assertArrayEquals(FtpsTestServer.contentOf(size), target.readBytes())
    }
}
