package org.filezilla.ftp.sftp

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.nio.file.Files

/**
 * A real SSH/SFTP server, in process, for the engine's integration tests.
 *
 * A live server rather than a mock for the same reason the FTP tests use a
 * live FTPS server: a mock agrees with whatever the code does, and agreeing is
 * the failure mode worth catching. Apache MINA SSHD is the reference Java
 * implementation, so passing against it is real evidence the engine speaks the
 * protocol.
 */
class SftpTestServer(
    val user: String = "test",
    val password: String = "s3cret",
) {
    /** The directory the server exposes as its root. Tests write files here. */
    val root: File = Files.createTempDirectory("sftp-test").toFile()

    private val sshd: SshServer = SshServer.setUpDefaultServer().apply {
        port = 0
        // Kept in memory and regenerated per server, so each test run gets its
        // own host key -- which is what the host-key-trust tests rely on.
        keyPairProvider = SimpleGeneratorHostKeyProvider()
        passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == user && p == password }
        subsystemFactories = listOf(SftpSubsystemFactory())
        fileSystemFactory = VirtualFileSystemFactory(root.toPath())
    }

    var port: Int = 0
        private set

    fun start() {
        sshd.start()
        port = sshd.port
    }

    fun stop() {
        runCatching { sshd.stop(true) }
        root.deleteRecursively()
    }

    /** Writes a file of [size] bytes of deterministic content under the root. */
    fun putFile(path: String, size: Int): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size) { (it % 251).toByte() })
        return file
    }

    /** Settings that reach this server, optionally pinning a host key. */
    fun settings(knownHostKey: String? = null): SftpSettings = SftpSettings(
        host = "127.0.0.1",
        port = port,
        user = user,
        password = password,
        knownHostKey = knownHostKey,
    )

    /**
     * The fingerprint this server presents, learned the way the app learns it:
     * the first connection is refused and carries the key.
     */
    fun discoverHostKey(): String = try {
        SftpEngine(settings()).use { it.connect() }
        error("expected the first connection to be refused for an unknown host key")
    } catch (e: HostKeyNotTrusted) {
        e.hostKey.fingerprint
    }
}
