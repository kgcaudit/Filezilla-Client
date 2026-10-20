package org.filezilla.ftp.testing

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * The real WebDAV path, proven against Apache `mod_dav` in a container.
 *
 * This is the harness a WebDAV client is written against: it exercises every
 * verb such a client needs -- OPTIONS to find the server, PROPFIND to list,
 * MKCOL to make a folder, PUT/GET to move a file, a ranged GET for resuming a
 * download, MOVE to rename, DELETE to remove -- against a server that is the
 * real thing, not a stub. Until the client exists these stand on their own,
 * proving the environment can verify it; once it does, it is checked here too.
 *
 * Skips when Docker is not available, the same bargain the FTP compatibility
 * tests make with their binaries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebDavSmokeTest {

    private val server = DockerServer(
        name = "olo-test-webdav",
        image = "bytemark/webdav",
        ports = mapOf(18080 to 80),
        env = mapOf("USERNAME" to USER, "PASSWORD" to PASS),
    )

    private val base get() = "http://127.0.0.1:${server.hostPort(80)}"
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$USER:$PASS".toByteArray())
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeAll
    fun up() {
        assumeTrue(DockerServer.dockerAvailable(), "Docker is not available")
        server.start()
    }

    @AfterAll
    fun down() {
        runCatching { server.stop() }
    }

    private fun request(method: String, path: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Authorization", auth)
            .method(
                method,
                if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body),
            )
        for ((k, v) in headers) builder.header(k, v)
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test
    fun `the server answers OPTIONS as a DAV server`() {
        val response = request("OPTIONS", "/")
        assertEquals(200, response.statusCode())
        // mod_dav advertises its class in the DAV header.
        val dav = response.headers().firstValue("DAV").orElse("")
        assertTrue(dav.contains("1"), "expected a DAV header, got '$dav'")
    }

    @Test
    fun `a file put up comes back down, and a ranged get resumes it`() {
        val body = "the quick brown fox".toByteArray()
        assertEquals(true, request("PUT", "/fox.txt", body).statusCode() in 200..201)

        val whole = request("GET", "/fox.txt")
        assertEquals(200, whole.statusCode())
        assertEquals("the quick brown fox", String(whole.body()))

        // A resume asks for the bytes after an offset; 206 Partial Content is
        // what makes resuming a half-finished download possible.
        val tail = request("GET", "/fox.txt", headers = mapOf("Range" to "bytes=10-"))
        assertEquals(206, tail.statusCode())
        assertEquals("brown fox", String(tail.body()))
    }

    @Test
    fun `a folder is made, listed, moved and removed`() {
        assertEquals(true, request("MKCOL", "/sub").statusCode() in 200..201)
        request("PUT", "/sub/a.txt", "a".toByteArray())

        // PROPFIND with Depth 1 lists the folder's immediate children.
        val listing = request("PROPFIND", "/sub/", headers = mapOf("Depth" to "1"))
        assertEquals(207, listing.statusCode())
        assertTrue(String(listing.body()).contains("a.txt"), "listing should name a.txt")

        // MOVE renames; the Destination is an absolute URL on the same server.
        val moved = request("MOVE", "/sub/a.txt", headers = mapOf("Destination" to "$base/sub/b.txt"))
        assertEquals(true, moved.statusCode() in 200..204)
        assertEquals(200, request("GET", "/sub/b.txt").statusCode())
        assertEquals(404, request("GET", "/sub/a.txt").statusCode())

        assertEquals(true, request("DELETE", "/sub/b.txt").statusCode() in 200..204)
        assertEquals(404, request("GET", "/sub/b.txt").statusCode())
    }

    private companion object {
        const val USER = "olo"
        const val PASS = "secret"
    }
}
