package org.filezilla.android.transfer

import org.filezilla.ftp.testing.DockerServer
import org.filezilla.ftp.webdav.WebDavSettings
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The WebDAV browsing session, proven against Apache mod_dav.
 *
 * [WebDavSession] is the thin half of WebDAV -- it tracks the current folder
 * (WebDAV has none of its own) and hands each name to the verified client. This
 * checks that glue end to end: connect, make a folder, see it, step in and out,
 * rename it, remove it. Skips when Docker is not there.
 */
class WebDavSessionTest {

    private fun session() = WebDavSession(
        WebDavSettings(
            host = "127.0.0.1",
            port = server.hostPort(80),
            https = false,
            user = "olo",
            password = "secret",
            basePath = "/",
        ),
    ).also { it.connect() }

    @Test
    fun `make a folder, see it, step in and out, rename and remove`() {
        val s = session()
        s.changeDirectory("/")

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
    }

    companion object {
        private val server = DockerServer(
            name = "olo-test-webdav-session",
            image = "bytemark/webdav",
            ports = mapOf(18083 to 80),
            env = mapOf("USERNAME" to "olo", "PASSWORD" to "secret"),
        )

        @BeforeClass
        @JvmStatic
        fun up() {
            assumeTrue("Docker is not available", DockerServer.dockerAvailable())
            server.start()
            // Apache opens the port a moment before it answers HTTP.
            var lastError: Exception? = null
            repeat(20) {
                try {
                    WebDavSession(
                        WebDavSettings("127.0.0.1", server.hostPort(80), false, "olo", "secret", "/"),
                    ).connect()
                    return
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(500)
                }
            }
            throw lastError ?: IllegalStateException("WebDAV server did not become ready")
        }

        @AfterClass
        @JvmStatic
        fun down() {
            runCatching { server.stop() }
        }
    }
}
