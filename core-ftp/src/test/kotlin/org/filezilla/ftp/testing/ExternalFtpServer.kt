package org.filezilla.ftp.testing

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** A real FTP server implementation the engine is checked against. */
enum class ServerKind(val displayName: String, val binary: File) {
    VSFTPD("vsftpd", File("/usr/sbin/vsftpd")),

    // Extracted rather than installed: vsftpd and Pure-FTPd both provide
    // `ftp-server` and conflict, so only one can be a package at a time.
    PURE_FTPD("Pure-FTPd", File("/opt/pure-ftpd/usr/sbin/pure-ftpd")),
}

/**
 * Runs a real, third-party FTP server for the duration of a test.
 *
 * The Python server in `ftps-server/` is exact and scriptable, which is what
 * the resume tests need -- it can be told to fake `REST` or drop a connection
 * on cue. What it cannot do is disagree with the engine the way a real
 * implementation does. vsftpd does not advertise `MLSD` at all, so it forces
 * the `LIST` path and its human-readable output through the guessing parser;
 * Pure-FTPd advertises `MLSD` and `MFMT` and takes neither. Those differences
 * are the whole content of the compatibility matrix, and no amount of testing
 * against one server produces them.
 *
 * Both servers need root and a real login account, so tests skip themselves
 * when [isAvailable] is false -- the same bargain the Python server makes.
 * `setup-compat.sh` is what makes it true.
 */
class ExternalFtpServer(
    val kind: ServerKind,
    /**
     * Refuse a data connection that did not resume the control connection's
     * TLS session. vsftpd's own `require_ssl_reuse`, on by default there, and
     * the reason a client without session resumption cannot so much as list a
     * directory. Pure-FTPd has no equivalent setting.
     */
    private val requireSslReuse: Boolean = true,
) {

    var port: Int = 0
        private set

    private var process: Process? = null
    private var configFile: File? = null

    /**
     * The server's root, which is also the login account's home directory.
     *
     * Fixed rather than a fresh temp directory per run because Pure-FTPd takes
     * its root from the account's `passwd` entry, not from a command line: a
     * per-run root would mean rewriting `/etc/passwd` for every test. It is
     * emptied at [start] instead.
     */
    val root: File = File(HOME)

    val user get() = USER
    val password get() = PASSWORD

    fun start() {
        check(isAvailable(kind)) { "${kind.displayName} is not set up; run setup-compat.sh" }
        root.deleteRecursively()
        root.mkdirs()
        chown(root)

        port = freePort()
        val pasvLow = freePort()
        val process = when (kind) {
            ServerKind.VSFTPD -> startVsftpd(pasvLow)
            ServerKind.PURE_FTPD -> startPureFtpd(pasvLow)
        }
        this.process = process
        awaitListening()
    }

    private fun startVsftpd(pasvLow: Int): Process {
        val config = File.createTempFile("vsftpd", ".conf").apply {
            writeText(
                """
                listen=YES
                listen_ipv6=NO
                listen_address=127.0.0.1
                listen_port=$port
                anonymous_enable=NO
                local_enable=YES
                write_enable=YES
                local_root=${root.absolutePath}
                pasv_enable=YES
                pasv_address=127.0.0.1
                pasv_min_port=$pasvLow
                pasv_max_port=${pasvLow + 40}
                ssl_enable=YES
                force_local_data_ssl=YES
                force_local_logins_ssl=YES
                rsa_cert_file=${certificate.absolutePath}
                rsa_private_key_file=${privateKey.absolutePath}
                require_ssl_reuse=${if (requireSslReuse) "YES" else "NO"}
                seccomp_sandbox=NO
                secure_chroot_dir=/var/run/vsftpd/empty
                pam_service_name=vsftpd
                background=NO
                """.trimIndent(),
            )
        }
        configFile = config
        val builder = ProcessBuilder(kind.binary.absolutePath, config.absolutePath)
        // vsftpd writes its startup errors to *stdin*, not stderr, so a
        // failure is silent unless fd 0 is redirected somewhere readable.
        // Two hours were spent learning that; the redirect stays.
        builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.to(diagnostics))
        return builder.start()
    }

    private fun startPureFtpd(pasvLow: Int): Process {
        val combined = File.createTempFile("pure-ftpd", ".pem").apply {
            // Pure-FTPd wants the key and certificate in one file.
            writeText(privateKey.readText() + certificate.readText())
            setReadable(false, false)
            setReadable(true, true)
        }
        configFile = combined
        val builder = ProcessBuilder(
            kind.binary.absolutePath,
            "-S", "127.0.0.1,$port",
            "-l", "unix",
            "-E",
            "-j",
            "-P", "127.0.0.1",
            "-p", "$pasvLow:${pasvLow + 40}",
            "-Y", "1",
            "--certfile=${combined.absolutePath}",
            // Foreground, so the process handle is the server and stopping it
            // actually stops it.
            "-b",
        )
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.to(diagnostics))
        return builder.start()
    }

    fun stop() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        // Pure-FTPd forks a supervisor that outlives the handle we hold, and a
        // leftover one holds the port against the next test.
        if (kind == ServerKind.PURE_FTPD) {
            runCatching {
                ProcessBuilder("pkill", "-f", "pure-ftpd").start().waitFor(5, TimeUnit.SECONDS)
            }
        }
        configFile?.delete()
        configFile = null
    }

    /** Creates a file of [size] bytes with the same content the other tests use. */
    fun putFile(name: String, size: Int): File {
        val file = File(root, name)
        file.parentFile.mkdirs()
        file.writeBytes(FtpsTestServer.contentOf(size))
        chown(file)
        return file
    }

    private fun awaitListening() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val live = process?.isAlive ?: false
            if (!live) {
                throw IllegalStateException(
                    "${kind.displayName} exited during startup:\n${diagnostics.readText()}",
                )
            }
            val connected = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
            }.isSuccess
            if (connected) return
            Thread.sleep(100)
        }
        val trace = diagnostics.readText()
        stop()
        throw IllegalStateException("${kind.displayName} did not start listening:\n$trace")
    }

    private fun chown(target: File) {
        runCatching {
            ProcessBuilder("chown", "-R", "$USER:", target.absolutePath)
                .start().waitFor(10, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val USER = "fztest"
        private const val PASSWORD = "fztest"
        private const val HOME = "/srv/fztest"

        private val diagnostics: File by lazy {
            File.createTempFile("ftp-server", ".log").apply { deleteOnExit() }
        }

        private val certificateDir: File by lazy {
            val url = ExternalFtpServer::class.java.classLoader
                .getResource("ftps-server/ftps_server.py")
                ?: error("ftps-server resources are missing from the test classpath")
            File(url.toURI()).parentFile
        }

        private val certificate: File get() = File(certificateDir, "cert.pem")
        private val privateKey: File get() = File(certificateDir, "key.pem")

        /**
         * Both servers bind privileged machinery and authenticate against the
         * system account database, so this is only true on a machine where
         * `setup-compat.sh` has been run as root.
         */
        fun isAvailable(kind: ServerKind): Boolean =
            kind.binary.canExecute() &&
                certificate.isFile &&
                privateKey.isFile &&
                File(HOME).isDirectory &&
                isRoot

        private val isRoot: Boolean by lazy {
            runCatching {
                val process = ProcessBuilder("id", "-u").start()
                process.waitFor(5, TimeUnit.SECONDS)
                process.inputStream.bufferedReader().readText().trim() == "0"
            }.getOrDefault(false)
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
