package org.filezilla.ftp.transfer

import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Stopping a transfer that has stopped receiving.
 *
 * The bug these cover, reported from a phone: the network went, the card sat
 * at the same byte count showing the same speed, and the pause button did
 * nothing. Nothing was wrong with the pause -- it was delivered through the
 * progress callback, and the engine calls that as bytes arrive. No bytes, no
 * pause.
 *
 * So the server here stops sending without closing anything, which is what
 * the phone saw, and the read parks exactly as it did on the device. The read
 * timeout is left long on purpose: it is what the old code waited for, and a
 * test that shortened it would pass on the bug.
 */
class AbortIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private var server: FtpsTestServer? = null

    /** Far longer than any of these tests should take; see the class comment. */
    private val readTimeout = 20_000

    @AfterEach
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun startServer(stallAfterBytes: Int): FtpsTestServer {
        assumeTrue(
            FtpsTestServer.isAvailable,
            "FTPS test server not set up; run core-ftp/src/test/resources/ftps-server/setup.sh",
        )
        return FtpsTestServer(stallAfterBytes = stallAfterBytes).also {
            it.start()
            server = it
        }
    }

    private fun settingsFor(s: FtpsTestServer) = FtpSettings(
        host = "127.0.0.1",
        port = s.port,
        user = s.user,
        password = s.password,
        security = FtpSecurity.EXPLICIT_TLS,
        trustAllCertificates = true,
        readTimeoutMillis = readTimeout,
    )

    @Test
    fun `a stop ends a transfer that is receiving nothing`() {
        val s = startServer(stallAfterBytes = 64 * 1024)
        s.putFile("stalled.bin", 8 * 1024 * 1024)
        val target = File(tempDir.toFile(), "stalled.bin")
        val abort = TransferAbort()

        val stalled = CountDownLatch(1)
        val received = AtomicLong()
        // Counts down once the transfer has as many bytes as the server will
        // ever give it, so the stop lands while the read is parked rather than
        // while data is still moving -- which is the whole point.
        val progress = TransferProgressListener { transferred, _, _ ->
            received.set(transferred)
            if (transferred >= 64 * 1024) stalled.countDown()
        }

        var failure: Throwable? = null
        val transfer = Thread {
            failure = runCatching {
                ResilientTransfer(
                    settings = settingsFor(s),
                    // One attempt, so what this measures is the stop and not
                    // the retry policy's patience.
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                    abort = abort,
                ).download("stalled.bin", progress = progress) { target.asTransferWriter() }
            }.exceptionOrNull()
        }
        transfer.start()

        assertTrue(
            stalled.await(30, TimeUnit.SECONDS),
            "the server never stalled; got ${received.get()} bytes",
        )
        val startedStopAt = System.currentTimeMillis()
        abort.abortAndStop()
        transfer.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse(transfer.isAlive, "the transfer was still running 10s after being stopped")
        val took = System.currentTimeMillis() - startedStopAt
        assertTrue(
            took < readTimeout,
            "stopping took ${took}ms; it waited out the ${readTimeout}ms read timeout instead of " +
                "ending the read",
        )
        assertTrue(failure != null, "a stopped transfer must not report success")
    }

    @Test
    fun `a stopped transfer does not reconnect and carry on`() {
        val s = startServer(stallAfterBytes = 64 * 1024)
        s.putFile("stalled.bin", 8 * 1024 * 1024)
        val target = File(tempDir.toFile(), "stalled.bin")
        val abort = TransferAbort()

        val stalled = CountDownLatch(1)
        val attempts = AtomicLong()
        val progress = TransferProgressListener { transferred, _, _ ->
            if (transferred >= 64 * 1024) stalled.countDown()
        }

        val transfer = Thread {
            runCatching {
                ResilientTransfer(
                    settings = settingsFor(s),
                    // Room to retry, so that not retrying is a decision rather
                    // than the policy running out.
                    retryPolicy = RetryPolicy(maxAttempts = 5),
                    // Counted here because a reconnect has to come through the
                    // backoff first.
                    sleep = { attempts.incrementAndGet() },
                    abort = abort,
                ).download("stalled.bin", progress = progress) { target.asTransferWriter() }
            }
        }
        transfer.start()

        assertTrue(stalled.await(30, TimeUnit.SECONDS), "the server never stalled")
        abort.abortAndStop()
        transfer.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse(transfer.isAlive, "the transfer was still running 10s after being stopped")
        // Closing the socket looks exactly like the dropped connection the
        // retry loop exists to recover from. Without the abort being consulted
        // there, a pause would be answered by reconnecting -- the pause button
        // undoing itself.
        assertTrue(
            attempts.get() == 0L,
            "the stopped transfer waited out a backoff to reconnect ${attempts.get()} time(s)",
        )
    }
}
