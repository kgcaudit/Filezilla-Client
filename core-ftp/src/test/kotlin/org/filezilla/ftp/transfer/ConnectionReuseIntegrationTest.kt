package org.filezilla.ftp.transfer

import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Connection reuse, against a live server rather than a mock.
 *
 * The whole point of [ControlConnections] is that a queue of small files stops
 * paying a login per file. Whether that actually holds is a question about the
 * FTP conversation -- whether a server will take a second `RETR` on a
 * connection that just finished one -- so it is worth asking a real server.
 */
class ConnectionReuseIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: FtpsTestServer

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
        pinnedCertificate = FtpsTestServer.fingerprint,
        maxRetries = 1,
    )

    /** Keeps one connection across transfers, and counts what it opened. */
    private class Reusing(
        private val settings: FtpSettings,
        private val capabilities: ServerCapabilities,
    ) : ControlConnections, AutoCloseable {

        var opened = 0
            private set

        var discarded = 0
            private set

        private var held: FtpControlConnection? = null

        override fun acquire(): FtpControlConnection {
            held?.let { return it }
            opened++
            return FtpControlConnection(settings, capabilities).also {
                it.connect()
                it.login()
                held = it
            }
        }

        override fun release(connection: FtpControlConnection, reusable: Boolean) {
            if (reusable) return
            discarded++
            runCatching { connection.close() }
            held = null
        }

        override fun close() {
            runCatching { held?.close() }
            held = null
        }
    }

    @Test
    fun `three downloads over one connection open one connection`() {
        val capabilities = ServerCapabilities()
        val sizes = mapOf("a.bin" to 40_000, "b.bin" to 50_000, "c.bin" to 60_000)
        sizes.forEach { (name, size) -> server.putFile(name, size) }

        Reusing(settings(), capabilities).use { connections ->
            val transfer = ResilientTransfer(
                settings = settings(),
                capabilities = capabilities,
                connections = connections,
            )

            for ((name, size) in sizes) {
                val target = File(tempDir.toFile(), name)
                val result = transfer.download(name) { target.asTransferWriter() }

                assertEquals(size.toLong(), result.outcome.bytesTransferred, name)
                // The bytes, not just the byte count: a reused connection that
                // had left state behind would show up here first.
                assertArrayEquals(FtpsTestServer.contentOf(size), target.readBytes(), name)
            }

            assertEquals(1, connections.opened)
            assertEquals(0, connections.discarded)
        }
    }

    /**
     * A file that is not there fails the attempt. The connection itself is
     * fine, but the engine cannot know that, and handing a suspect connection
     * to the next transfer is how one failure becomes several.
     */
    @Test
    fun `a failed transfer gives back a connection that is not reused`() {
        val capabilities = ServerCapabilities()

        Reusing(settings(), capabilities).use { connections ->
            val transfer = ResilientTransfer(
                settings = settings(),
                capabilities = capabilities,
                retryPolicy = RetryPolicy(maxAttempts = 1),
                connections = connections,
            )

            assertThrows(Exception::class.java) {
                transfer.download("missing.bin") {
                    File(tempDir.toFile(), "missing.bin").asTransferWriter()
                }
            }

            assertEquals(1, connections.discarded)

            // And the next transfer still works, on a connection of its own.
            server.putFile("after.bin", 30_000)
            val target = File(tempDir.toFile(), "after.bin")
            val result = transfer.download("after.bin") { target.asTransferWriter() }

            assertEquals(30_000L, result.outcome.bytesTransferred)
            assertEquals(2, connections.opened)
        }
    }

    /** The default is still one connection per attempt, closed afterwards. */
    @Test
    fun `without a reusing source each transfer opens its own connection`() {
        val capabilities = ServerCapabilities()
        server.putFile("plain.bin", 20_000)

        val transfer = ResilientTransfer(settings = settings(), capabilities = capabilities)
        val target = File(tempDir.toFile(), "plain.bin")

        val result = transfer.download("plain.bin") { target.asTransferWriter() }

        assertEquals(20_000L, result.outcome.bytesTransferred)
        assertTrue(target.readBytes().contentEquals(FtpsTestServer.contentOf(20_000)))
    }
}
