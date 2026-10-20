package org.filezilla.ftp.smb

import org.filezilla.ftp.io.asTransferReader
import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.testing.DockerServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * SMB 3 against Samba, the proof the Android crypto in
 * `org.filezilla.ftp.smb.crypto` works on the wire and not just against test
 * vectors. Samba is told to demand each thing an SMB 3 dialect uses, so a round
 * trip that completes has exercised it for real:
 *
 *  - **mandatory signing** -> AES-CMAC, the signature on every SMB 3 request.
 *  - **an encrypted share at 3.1.1** -> AES-GCM, plus the SHA-512 pre-auth
 *    integrity and SP800-108 key derivation 3.1.1 brings.
 *  - **an encrypted share at 3.0** -> AES-CCM, the dialect that has no GCM.
 *
 * Encryption is set on the share, which is how a real encrypted share is
 * configured and the shape smbj drives: the server flags the tree connect
 * encrypted and smbj encrypts from there. A green round trip to a share that
 * `testparm` confirms demands encryption is the proof -- plaintext would be
 * refused. (Session-global `smb encrypt = required` is a separate matter: smbj
 * does not advertise session encryption at negotiate, so Samba turns the session
 * away before any share is reached. That is an smbj limitation, not this crypto's
 * -- it happens with smbj's own BouncyCastle provider too -- so it is left out.)
 *
 * Skips without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Smb3IntegrationTest {

    @BeforeAll
    fun requireDocker() = assumeTrue(DockerServer.dockerAvailable(), "Docker is not available")

    @Test
    fun `SMB 3 negotiates and round-trips`(@TempDir dir: Path) {
        withSamba(listOf("server min protocol = SMB3")) { settings -> roundTrip(settings, dir, "s3.bin") }
    }

    @Test
    fun `a plain SMB 2_1 server still works with encryption enabled client-side`(@TempDir dir: Path) {
        // The client asks for encryption, but a 2.1-only server cannot do it; the
        // connection must still succeed unencrypted rather than be refused.
        withSamba(
            listOf("server min protocol = SMB2", "server max protocol = SMB2_10"),
        ) { settings -> roundTrip(settings, dir, "smb21.bin") }
    }

    @Test
    fun `an SMB 3 server with encryption disabled still works`(@TempDir dir: Path) {
        // The client asks for encryption; a server that forbids it must still be
        // usable, unencrypted, rather than refused.
        withSamba(
            listOf("server min protocol = SMB3", "smb encrypt = disabled"),
        ) { settings -> roundTrip(settings, dir, "noenc.bin") }
    }

    @Test
    fun `SMB 3 with mandatory signing uses AES-CMAC and round-trips`(@TempDir dir: Path) {
        withSamba(
            listOf("server min protocol = SMB3", "server signing = mandatory"),
        ) { settings -> roundTrip(settings, dir, "sign.bin") }
    }

    @Test
    fun `an encrypted share at SMB 3_1_1 uses AES-GCM and round-trips`(@TempDir dir: Path) {
        withSamba(listOf("server min protocol = SMB3_11"), shareEncrypted = true) { settings ->
            roundTrip(settings, dir, "gcm.bin")
        }
    }

    @Test
    fun `an encrypted share at SMB 3_0 uses AES-CCM and round-trips`(@TempDir dir: Path) {
        withSamba(
            listOf("server min protocol = SMB3_00", "server max protocol = SMB3_00"),
            shareEncrypted = true,
        ) { settings -> roundTrip(settings, dir, "ccm.bin") }
    }

    private fun roundTrip(settings: SmbSettings, dir: Path, name: String) {
        val payload = Random(42).nextBytes(300_000)
        val local = File(dir.toFile(), "src-$name").apply { writeBytes(payload) }
        SmbResilientTransfer(settings).upload("/$name", resume = false, progress = null) { local.asTransferReader() }

        val back = File(dir.toFile(), "back-$name")
        SmbResilientTransfer(settings).download("/$name", forcedResumeOffset = null, progress = null) {
            back.asTransferWriter()
        }
        assertArrayEquals(payload, back.readBytes(), "the round trip must return the same bytes")
    }

    private fun withSamba(globals: List<String>, shareEncrypted: Boolean = false, block: (SmbSettings) -> Unit) {
        val hostPort = 11450 + ports.getAndIncrement()
        val name = "olo-test-smb3-$hostPort"
        val args = buildList {
            add("-p")
            add("-u"); add("$USER;$PASS")
            add("-s"); add("$SHARE;/share;yes;no;no;$USER")
            add("-w"); add("WORKGROUP")
            for (g in globals) { add("-g"); add(g) }
        }
        val server = DockerServer(
            name = name,
            image = "dperson/samba",
            ports = mapOf(hostPort to 445),
            env = mapOf("USERID" to "0", "GROUPID" to "0"),
            args = args,
        )
        server.start()
        try {
            val settings = SmbSettings(
                host = "127.0.0.1",
                port = server.hostPort(445),
                user = USER,
                password = PASS,
                domain = "WORKGROUP",
                share = SHARE,
            )
            waitUntilReady(settings)
            if (shareEncrypted) requireShareEncryption(name)
            block(settings)
        } finally {
            runCatching { server.stop() }
        }
    }

    /** Waits for Samba to answer a login; the body runs once so a failure is not retried away. */
    private fun waitUntilReady(settings: SmbSettings) {
        var lastError: Exception? = null
        repeat(40) {
            try {
                SmbClient(settings).use { it.connect(); it.list("/") }
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw lastError ?: IllegalStateException("the SMB 3 share did not become ready")
    }

    /** Turns on encryption for the share and confirms, via testparm, that it took. */
    private fun requireShareEncryption(container: String) {
        val enable =
            "sed -i '/^\\[$SHARE\\]/a\\   smb encrypt = required' /etc/samba/smb.conf && smbcontrol smbd reload-config"
        exec(container, "sh", "-c", enable)
        val effective = exec(container, "testparm", "-s", "--section-name=$SHARE", "--parameter-name=smb encrypt")
        check(effective.trim().contains("required", ignoreCase = true)) {
            "the share was not set to require encryption; testparm said '${effective.trim()}'"
        }
        Thread.sleep(1000)
    }

    private fun exec(container: String, vararg command: String): String {
        val process = ProcessBuilder(listOf("docker", "exec", container) + command)
            .redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        process.waitFor()
        return out
    }

    private companion object {
        const val USER = "olo"
        const val PASS = "secret"
        const val SHARE = "share"
        val ports = AtomicInteger(0)
    }
}
