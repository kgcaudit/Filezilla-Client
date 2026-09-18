package org.filezilla.ftp.testing

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the Python FTPS server in `src/test/resources/ftps-server` for the
 * duration of a test.
 *
 * The integration tests deliberately talk to a real FTPS server rather than a
 * mock: resume is exactly the area where a mock would agree with whatever the
 * engine does, and the failures worth catching are the ones a real server
 * produces -- a refused `REST`, a `522` for an unresumed TLS session, a
 * transfer that ends one byte short.
 *
 * Tests that need it are skipped when [isAvailable] is false, so a checkout
 * without the Python environment still builds and runs its unit tests.
 */
class FtpsTestServer(
    private val requireSslReuse: Boolean = true,
    private val tlsMax: String = "1.2",
    private val noTicket: Boolean = true,
    /**
     * Acknowledge `REST` with 350 and then ignore the offset, reproducing the
     * 2 GB / 4 GB offset bug that [FtpTransferEngine]'s probe exists to catch.
     */
    private val ignoreRest: Boolean = false,
    /**
     * Cut the data connection after this many bytes, for the first
     * [dropTimes] transfers, reproducing a connection that dies partway.
     */
    private val dropAfterBytes: Int = 0,
    private val dropTimes: Int = 0,
) {
    lateinit var root: File
        private set

    var port: Int = 0
        private set

    private var process: Process? = null

    val user get() = "test"
    val password get() = "test"

    fun start() {
        check(isAvailable) { "test server environment is missing; run setup.sh" }
        root = Files.createTempDirectory("ftps-root").toFile()
        port = freePort()
        val pasvLow = freePort()

        val builder = ProcessBuilder(
            python.absolutePath,
            File(serverDir, "ftps_server.py").absolutePath,
        )
        builder.environment().apply {
            put("FTPS_PORT", port.toString())
            put("FTPS_PASV_PORTS", "$pasvLow-${pasvLow + 20}")
            put("FTPS_ROOT", root.absolutePath)
            put("FTPS_CERT_DIR", serverDir.absolutePath)
            put("REQUIRE_SSL_REUSE", if (requireSslReuse) "1" else "0")
            put("TLS_MAX", tlsMax)
            put("NO_TICKET", if (noTicket) "1" else "0")
            put("IGNORE_REST", if (ignoreRest) "1" else "0")
            put("DROP_AFTER_BYTES", dropAfterBytes.toString())
            put("DROP_TIMES", dropTimes.toString())
        }
        builder.redirectErrorStream(false)
        val started = builder.start()
        process = started

        // Wait for the readiness marker rather than sleeping.
        val reader = started.inputStream.bufferedReader()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val line = reader.readLine() ?: break
            if (line.startsWith("READY")) return
        }
        val diagnostics = started.errorStream.bufferedReader().readText()
        stop()
        throw IllegalStateException("FTPS test server did not start:\n$diagnostics")
    }

    fun stop() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        if (::root.isInitialized) root.deleteRecursively()
    }

    /**
     * Creates a sparse file of [size] bytes, for the boundary tests that need a
     * file past 2 GB without writing 2 GB. The contents read back as zeroes.
     */
    fun putSparseFile(name: String, size: Long): File {
        val file = File(root, name)
        file.parentFile.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(size) }
        return file
    }

    /** Creates a file of [size] bytes with deterministic, position-derived content. */
    fun putFile(name: String, size: Int): File {
        val file = File(root, name)
        file.parentFile.mkdirs()
        file.writeBytes(contentOf(size))
        return file
    }

    companion object {
        private val serverDir: File by lazy {
            val url = FtpsTestServer::class.java.classLoader.getResource("ftps-server/ftps_server.py")
                ?: error("ftps-server resources are missing from the test classpath")
            File(url.toURI()).parentFile
        }

        private val python: File by lazy {
            System.getenv("FTPS_TEST_PYTHON")?.let { return@lazy File(it) }
            File(serverDir, "venv/bin/python")
        }

        /** True when the Python environment and certificate are both present. */
        val isAvailable: Boolean by lazy {
            python.canExecute() &&
                File(serverDir, "cert.pem").isFile &&
                File(serverDir, "key.pem").isFile
        }

        /**
         * Deterministic content: byte at position i is (i * 31 + 7) mod 251, so
         * a resumed transfer that splices at the wrong offset produces a
         * mismatch instead of accidentally matching.
         */
        fun contentOf(size: Int): ByteArray =
            ByteArray(size) { i -> ((i.toLong() * 31 + 7) % 251).toByte() }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
