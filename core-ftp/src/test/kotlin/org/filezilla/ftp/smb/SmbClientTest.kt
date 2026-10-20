package org.filezilla.ftp.smb

import org.filezilla.ftp.testing.DockerServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream

/**
 * The SMB client, proven against Samba with smbj on its JCE provider.
 *
 * The JCE-only, SMB-2.1 configuration in [SmbClient] is the one that runs on
 * Android, so it is the one proved here against a real Samba: connect and
 * authenticate, list the share, make a folder, write a file and read it back,
 * learn its size, rename it, and remove it. Skips when Docker is not available.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmbClientTest {

    private val server = samba("olo-test-smb-client", 11445)

    @BeforeAll
    fun up() {
        assumeTrue(DockerServer.dockerAvailable(), "Docker is not available")
        server.start()
        awaitReady(server)
    }

    @AfterAll
    fun down() {
        runCatching { server.stop() }
    }

    private fun client() = SmbClient(settingsFor(server)).also { it.connect() }

    @Test
    fun `a folder is made, listed, stepped through, renamed and removed`() {
        val smb = client()
        try {
            if (smb.list("/").any { it.name == "reports" }) smb.removeDirectory("/reports")
            smb.makeDirectory("/reports")
            assertTrue(
                smb.list("/").any { it.name == "reports" && it.isDirectory },
                "the new folder should list at the share root",
            )

            smb.rename("/reports", "/papers")
            val names = smb.list("/").map { it.name }
            assertTrue(names.contains("papers"), "renamed folder shows")
            assertFalse(names.contains("reports"), "old name is gone")

            smb.removeDirectory("/papers")
            assertFalse(smb.list("/").any { it.name == "papers" }, "removed folder is gone")
        } finally {
            smb.close()
        }
    }

    @Test
    fun `a file written to the share lists, reads back, fingerprints and deletes`() {
        val smb = client()
        try {
            if (!smb.list("/").any { it.name == "box" && it.isDirectory }) smb.makeDirectory("/box")
            val payload = "hello over smb".toByteArray()

            smb.upload("/box/note.txt", payload.inputStream(), offset = 0) {}
            assertTrue(
                smb.list("/box").any { it.name == "note.txt" && !it.isDirectory },
                "listing names note.txt",
            )

            val print = smb.fingerprint("/box/note.txt")
            assertEquals(payload.size.toLong(), print.size, "fingerprint reports the file size")

            val sink = ByteArrayOutputStream()
            val total = smb.download("/box/note.txt", offset = 0, out = sink) {}
            assertEquals(payload.size.toLong(), total, "download reports the whole size")
            assertEquals("hello over smb", sink.toString("UTF-8"))

            smb.deleteFile("/box/note.txt")
            assertFalse(smb.list("/box").any { it.name == "note.txt" }, "the file is gone after delete")
        } finally {
            smb.close()
        }
    }

    @Test
    fun `a wrong password is reported as a login failure, not a raw status`() {
        val bad = SmbClient(settingsFor(server).copy(password = "wrong"))
        val e = assertThrows<SmbStatusException> { bad.connect() }
        assertEquals(SmbStatusException.Kind.LOGIN_FAILED, e.kind)
    }

    @Test
    fun `a missing share is reported as share-not-found`() {
        val bad = SmbClient(settingsFor(server).copy(share = "nosuchshare"))
        val e = assertThrows<SmbStatusException> { bad.connect() }
        assertEquals(SmbStatusException.Kind.SHARE_NOT_FOUND, e.kind)
    }

    @Test
    fun `a download from an offset returns only the tail`() {
        val smb = client()
        try {
            if (!smb.list("/").any { it.name == "box" && it.isDirectory }) smb.makeDirectory("/box")
            val payload = "0123456789abcdef".toByteArray()
            smb.upload("/box/seek.bin", payload.inputStream(), offset = 0) {}

            val sink = ByteArrayOutputStream()
            smb.download("/box/seek.bin", offset = 10, out = sink) {}
            assertEquals("abcdef", sink.toString("UTF-8"), "the read starts at the offset")

            smb.deleteFile("/box/seek.bin")
        } finally {
            smb.close()
        }
    }

    private companion object {
        const val USER = "olo"
        const val PASS = "secret"
        const val SHARE = "share"

        fun samba(name: String, hostPort: Int) = DockerServer(
            name = name,
            image = "dperson/samba",
            ports = mapOf(hostPort to 445),
            env = mapOf("USERID" to "0", "GROUPID" to "0"),
            args = listOf(
                "-p",
                "-u", "$USER;$PASS",
                "-s", "$SHARE;/share;yes;no;no;$USER",
                "-w", "WORKGROUP",
            ),
        )

        fun settingsFor(server: DockerServer) = SmbSettings(
            host = "127.0.0.1",
            port = server.hostPort(445),
            user = USER,
            password = PASS,
            domain = "WORKGROUP",
            share = SHARE,
        )

        /** Samba answers the port a moment before it answers a login. */
        fun awaitReady(server: DockerServer) {
            var lastError: Exception? = null
            repeat(30) {
                try {
                    SmbClient(settingsFor(server)).use { it.connect() }
                    return
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(500)
                }
            }
            throw lastError ?: IllegalStateException("the SMB share did not become ready")
        }
    }
}
