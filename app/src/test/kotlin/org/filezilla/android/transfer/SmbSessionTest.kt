package org.filezilla.android.transfer

import org.filezilla.ftp.smb.SmbSettings
import org.filezilla.ftp.testing.DockerServer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The SMB browsing session, proven against Samba.
 *
 * [SmbSession] is the thin half of SMB -- it tracks the current folder and hands
 * each name to the verified client. This checks that glue end to end: connect,
 * make a folder, see it, step in and out, rename it, remove it. Skips when
 * Docker is not there.
 */
class SmbSessionTest {

    private fun session() = SmbSession(settings(server)).also { it.connect() }

    @Test
    fun `make a folder, see it, step in and out, rename and remove`() {
        val s = session()
        try {
            s.changeDirectory("/")
            if (s.list().any { it.name == "reports" }) s.removeDirectory("reports")

            s.createDirectory("reports")
            assertTrue("the new folder should list", s.list().any { it.name == "reports" && it.isDirectory })

            assertEquals("/reports", s.changeDirectory("/reports"))
            assertEquals("/reports", s.currentDirectory())
            s.changeToParent()
            assertEquals("/", s.currentDirectory())

            s.rename("reports", "papers")
            val names = s.list().map { it.name }
            assertTrue("renamed folder shows", names.contains("papers"))
            assertFalse("old name is gone", names.contains("reports"))

            s.removeDirectory("papers")
            assertFalse("removed folder is gone", s.list().any { it.name == "papers" })
        } finally {
            s.close()
        }
    }

    companion object {
        private const val USER = "olo"
        private const val PASS = "secret"
        private const val SHARE = "share"

        private val server = DockerServer(
            name = "olo-test-smb-session",
            image = "dperson/samba",
            ports = mapOf(11447 to 445),
            env = mapOf("USERID" to "0", "GROUPID" to "0"),
            args = listOf(
                "-p",
                "-u", "$USER;$PASS",
                "-s", "$SHARE;/share;yes;no;no;$USER",
                "-w", "WORKGROUP",
            ),
        )

        private fun settings(server: DockerServer) = SmbSettings(
            host = "127.0.0.1",
            port = server.hostPort(445),
            user = USER,
            password = PASS,
            domain = "WORKGROUP",
            share = SHARE,
        )

        @BeforeClass
        @JvmStatic
        fun up() {
            assumeTrue("Docker is not available", DockerServer.dockerAvailable())
            server.start()
            // Samba opens the port a moment before it answers a login.
            var lastError: Exception? = null
            repeat(30) {
                try {
                    SmbSession(settings(server)).use { it.connect() }
                    return
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(500)
                }
            }
            throw lastError ?: IllegalStateException("SMB server did not become ready")
        }

        @AfterClass
        @JvmStatic
        fun down() {
            runCatching { server.stop() }
        }
    }
}
