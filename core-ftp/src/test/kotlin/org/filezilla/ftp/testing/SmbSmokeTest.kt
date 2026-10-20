package org.filezilla.ftp.testing

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The real SMB path, proven against Samba in a container with the smbj client.
 *
 * SMB has no in-process Java server worth the name, so the share is a real
 * Samba, and the client is [SMBClient] -- the pure-Java SMB2/3 library an SMB
 * engine would be built on. The test walks what such an engine does: connect
 * and authenticate, list a share, make a folder, write a file and read it back,
 * then remove it. Proving this is what makes an SMB engine verifiable rather
 * than hoped-at.
 *
 * Skips when Docker is not available.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmbSmokeTest {

    private val server = DockerServer(
        name = "olo-test-smb",
        image = "dperson/samba",
        ports = mapOf(11445 to 445),
        env = mapOf("USERID" to "0", "GROUPID" to "0"),
        args = listOf(
            "-p",
            "-u", "$USER;$PASS",
            "-s", "$SHARE;/share;yes;no;no;$USER",
            "-w", "WORKGROUP",
        ),
    )

    @BeforeAll
    fun up() {
        assumeTrue(DockerServer.dockerAvailable(), "Docker is not available")
        server.start()
    }

    @AfterAll
    fun down() {
        runCatching { server.stop() }
    }

    private fun <T> withShare(block: (DiskShare) -> T): T {
        val client = SMBClient()
        // Samba can take a moment past the port opening to answer a login, so
        // the connect/auth is given a few tries before it is called a failure.
        var lastError: Exception? = null
        repeat(20) {
            try {
                client.connect("127.0.0.1", server.hostPort(445)).use { connection ->
                    val session = connection.authenticate(
                        AuthenticationContext(USER, PASS.toCharArray(), null),
                    )
                    (session.connectShare(SHARE) as DiskShare).use { share ->
                        return block(share)
                    }
                }
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw lastError ?: IllegalStateException("could not reach the SMB share")
    }

    @Test
    fun `a file written to the share is listed and read back, then removed`() {
        val payload = "hello over smb".toByteArray()
        withShare { share ->
            if (!share.folderExists("box")) share.mkdir("box")

            share.openFile(
                "box\\note.txt",
                setOf(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            ).use { it.write(payload, 0) }

            val names = share.list("box").map { it.fileName }
            assertTrue(names.contains("note.txt"), "listing should name note.txt, got $names")

            val read = share.openFile(
                "box\\note.txt",
                setOf(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            ).use { it.inputStream.readBytes() }
            assertEquals("hello over smb", String(read))

            share.rm("box\\note.txt")
            assertFalse(
                share.list("box").map { it.fileName }.contains("note.txt"),
                "the file should be gone after rm",
            )
        }
    }

    private companion object {
        const val USER = "olo"
        const val PASS = "secret"
        const val SHARE = "share"
    }
}
