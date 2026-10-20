package org.filezilla.ftp.webdav

import org.filezilla.ftp.testing.DockerServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The WebDAV client, proven against Apache mod_dav in a container.
 *
 * Every verb the app needs, end to end against a real server: list, make a
 * folder, put a file and get it back, resume a download from an offset, read a
 * file's size and time, rename, delete. When the SMB/WebDAV clients are wired
 * into the app this is the proof the WebDAV half rests on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebDavClientTest {

    private val server = DockerServer(
        name = "olo-test-webdav-client",
        image = "bytemark/webdav",
        ports = mapOf(18081 to 80),
        env = mapOf("USERNAME" to "olo", "PASSWORD" to "secret"),
    )

    private lateinit var client: WebDavClient

    @BeforeAll
    fun up() {
        assumeTrue(DockerServer.dockerAvailable(), "Docker is not available")
        server.start()
        client = WebDavClient(
            WebDavSettings(
                host = "127.0.0.1",
                port = server.hostPort(80),
                https = false,
                user = "olo",
                password = "secret",
                basePath = "/",
            ),
        )
        // Apache opens the port a moment before it answers HTTP.
        var lastError: Exception? = null
        repeat(20) {
            try {
                client.connect()
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw lastError ?: IllegalStateException("WebDAV server did not become ready")
    }

    @AfterAll
    fun down() {
        runCatching { server.stop() }
    }

    @Test
    fun `make a folder, put and list, read info, rename and delete`() {
        client.makeCollection("/work")
        client.upload("/work/a.txt", ByteArrayInputStream("hello".toByteArray()), 5) {}

        val names = client.list("/work").map { it.name }
        assertTrue(names.contains("a.txt"), "listing should name a.txt, got $names")

        val info = client.info("/work/a.txt")
        assertNotNull(info)
        assertEquals(5, info!!.size)
        assertFalse(info.isDirectory)
        assertNotNull(info.time, "mod_dav gives a last-modified time")

        client.move("/work/a.txt", "/work/b.txt")
        assertNull(client.info("/work/a.txt"))
        assertEquals(5, client.info("/work/b.txt")?.size)

        client.delete("/work/b.txt")
        assertNull(client.info("/work/b.txt"))
    }

    @Test
    fun `a folder lists as a directory and a file as a file`() {
        client.makeCollection("/kinds")
        client.makeCollection("/kinds/folder")
        client.upload("/kinds/file.bin", ByteArrayInputStream(ByteArray(3)), 3) {}

        val byName = client.list("/kinds").associateBy { it.name }
        assertTrue(byName["folder"]!!.isDirectory)
        assertFalse(byName["file.bin"]!!.isDirectory)
    }

    @Test
    fun `a download resumes from an offset`() {
        val payload = "the quick brown fox".toByteArray()
        client.makeCollection("/dl")
        client.upload("/dl/fox.txt", ByteArrayInputStream(payload), payload.size.toLong()) {}

        val whole = ByteArrayOutputStream()
        val total = client.download("/dl/fox.txt", offset = 0, out = whole) {}
        assertEquals("the quick brown fox", whole.toString())
        assertEquals(payload.size.toLong(), total)

        // Resuming from byte 10 should bring back only the tail.
        val tail = ByteArrayOutputStream()
        client.download("/dl/fox.txt", offset = 10, out = tail) {}
        assertEquals("brown fox", tail.toString())
    }

    @Test
    fun `a name with spaces and unicode is encoded and round-trips`() {
        client.makeCollection("/enc")
        val name = "주의 보고서 v2.txt"
        client.upload("/enc/$name", ByteArrayInputStream("x".toByteArray()), 1) {}
        val names = client.list("/enc").map { it.name }
        assertTrue(names.contains(name), "a spaced/unicode name should round-trip, got $names")
    }

    @Test
    fun `fingerprint reports size and time`() {
        client.makeCollection("/fp")
        client.upload("/fp/f.bin", ByteArrayInputStream(ByteArray(42)), 42) {}
        val print = client.fingerprint("/fp/f.bin")
        assertEquals(42L, print.size)
        assertNotNull(print.modifiedMillis)
    }
}
