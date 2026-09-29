package org.filezilla.ftp.sftp

import org.filezilla.ftp.io.asTransferReader
import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.transfer.TransferDisposition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

/** The SFTP engine driven against a live in-process SSH server. */
class SftpEngineIntegrationTest {

    private lateinit var server: SftpTestServer

    @BeforeEach
    fun setUp() {
        server = SftpTestServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    /**
     * The fingerprint this server presents, learned the way the app learns it:
     * the first connection is refused and carries the key.
     */
    private fun discoverHostKey(): String {
        val refused = assertThrows<HostKeyNotTrusted> {
            SftpEngine(server.settings()).use { it.connect() }
        }
        assertFalse(refused.changed, "a first meeting is not a changed key")
        assertTrue(refused.hostKey.fingerprint.startsWith("SHA256:"))
        return refused.hostKey.fingerprint
    }

    /** Connects with the host key already trusted, and runs [block]. */
    private fun <T> connected(block: (SftpEngine) -> T): T {
        val pin = discoverHostKey()
        return SftpEngine(server.settings(knownHostKey = pin)).use {
            it.connect()
            block(it)
        }
    }

    private fun List<DirectoryEntry>.byName(name: String) = firstOrNull { it.name == name }

    // ------------------------------------------------------------- host key

    @Test
    fun `first meeting is refused and carries a fingerprint`() {
        val fingerprint = discoverHostKey()
        assertTrue(fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun `connects when the host key matches the pin`() {
        val here = connected { it.currentDirectory() }
        assertEquals("/", here)
    }

    @Test
    fun `logs in with a private key`() {
        // A key pair the server is told to accept, given to the engine as a
        // PKCS#8 PEM -- the shape a user pastes out of a key file.
        val pair = java.security.KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        server.authorizePublicKey(pair.public)
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"

        server.putFile("keyed.bin", 128)
        val settings = server.settings(knownHostKey = server.discoverHostKey())
            .copy(password = "", privateKeyPem = pem)

        val names = SftpEngine(settings).use {
            it.connect()
            it.list().map { entry -> entry.name }
        }
        assertTrue("keyed.bin" in names)
    }

    @Test
    fun `reports a changed host key`() {
        val refused = assertThrows<HostKeyNotTrusted> {
            SftpEngine(server.settings(knownHostKey = "SHA256:this-is-not-the-real-key")).use { it.connect() }
        }
        assertTrue(refused.changed, "a wrong pin should read as a changed key")
        assertEquals("SHA256:this-is-not-the-real-key", refused.previouslyTrusted)
    }

    // ------------------------------------------------------------- browsing

    @Test
    fun `lists files and directories with sizes`() {
        server.putFile("alpha.bin", 1234)
        server.putFile("beta.bin", 99)
        File(server.root, "subdir").mkdirs()

        val entries = connected { it.list() }

        val alpha = entries.byName("alpha.bin")!!
        assertEquals(1234, alpha.size)
        assertFalse(alpha.isDirectory)
        assertTrue(alpha.hasTime, "SFTP listings carry a second-accurate time")
        assertEquals(99, entries.byName("beta.bin")!!.size)

        val sub = entries.byName("subdir")!!
        assertTrue(sub.isDirectory)

        assertNull(entries.byName("."))
        assertNull(entries.byName(".."))
    }

    @Test
    fun `handles filenames with spaces and unicode`() {
        server.putFile("a file with spaces.txt", 5)
        server.putFile("한글 파일.txt", 7)

        val entries = connected { it.list() }

        assertEquals(5, entries.byName("a file with spaces.txt")!!.size)
        assertEquals(7, entries.byName("한글 파일.txt")!!.size)
    }

    @Test
    fun `navigates directories`() {
        File(server.root, "pub/inner").mkdirs()
        server.putFile("pub/inner/deep.bin", 3)

        val names = connected { engine ->
            assertEquals("/", engine.currentDirectory())
            assertEquals("/pub/inner", engine.changeDirectory("pub/inner"))
            val inner = engine.list().map { it.name }
            engine.changeToParent()
            assertEquals("/pub", engine.currentDirectory())
            inner
        }
        assertEquals(listOf("deep.bin"), names)
    }

    @Test
    fun `creates removes renames and deletes`() {
        server.putFile("old.bin", 4)

        connected { engine ->
            engine.createDirectory("newdir")
            assertTrue(engine.list().byName("newdir")!!.isDirectory)

            engine.rename("old.bin", "new.bin")
            val afterRename = engine.list()
            assertNull(afterRename.byName("old.bin"))
            assertEquals(4, afterRename.byName("new.bin")!!.size)

            engine.deleteFile("new.bin")
            engine.removeDirectory("newdir")

            val afterDelete = engine.list()
            assertNull(afterDelete.byName("new.bin"))
            assertNull(afterDelete.byName("newdir"))
        }
    }

    @Test
    fun `changes permissions`() {
        server.putFile("script.sh", 10)
        connected { engine ->
            engine.changeMode("script.sh", "750")
            val mode = engine.list().byName("script.sh")!!.permissions
            assertNotNull(mode)
            // rwxr-x---, however the letters are framed.
            assertTrue(mode!!.contains("rwxr-x---"), "expected rwxr-x--- in $mode")
        }
    }

    @Test
    fun `a server-side failure is not an IO failure`() {
        connected { engine ->
            // Not an IOException: a connection pool must not retry "no such
            // file" as though the socket had dropped.
            assertThrows<SftpStatusException> { engine.changeDirectory("does-not-exist") }
            assertThrows<SftpStatusException> { engine.deleteFile("not-there.bin") }
        }
    }

    @Test
    fun `fingerprint reports size and a missing file reports nothing`() {
        server.putFile("here.bin", 512)
        connected { engine ->
            val present = engine.fingerprint("here.bin")
            assertEquals(512, present.size)
            assertNotNull(present.modifiedMillis)

            val absent = engine.fingerprint("gone.bin")
            assertNull(absent.size)
            assertFalse(absent.isUsable)
        }
    }

    // ---------------------------------------------------------- transferring

    @Test
    fun `downloads a file`() {
        val source = server.putFile("movie.bin", 40_000)
        val into = File(Files.createTempDirectory("dl").toFile(), "movie.bin")

        val outcome = connected { engine ->
            engine.download("movie.bin", into.asTransferWriter())
        }

        assertEquals(TransferDisposition.TRANSFERRED, outcome.disposition)
        assertEquals(40_000, outcome.bytesTransferred)
        assertArrayEquals(source.readBytes(), into.readBytes())
    }

    @Test
    fun `resumes a partial download`() {
        val source = server.putFile("big.bin", 50_000)
        val into = File(Files.createTempDirectory("dl").toFile(), "big.bin")
        // A partial file: the first 20_000 bytes are already correctly present.
        into.writeBytes(source.readBytes().copyOfRange(0, 20_000))

        val outcome = connected { engine ->
            engine.download("big.bin", into.asTransferWriter())
        }

        assertEquals(TransferDisposition.RESUMED, outcome.disposition)
        assertEquals(20_000, outcome.resumeOffset)
        assertEquals(30_000, outcome.bytesTransferred)
        assertArrayEquals(source.readBytes(), into.readBytes())
    }

    @Test
    fun `skips a download that is already complete`() {
        val source = server.putFile("done.bin", 8_000)
        val into = File(Files.createTempDirectory("dl").toFile(), "done.bin")
        into.writeBytes(source.readBytes())

        val outcome = connected { engine ->
            engine.download("done.bin", into.asTransferWriter())
        }

        assertEquals(TransferDisposition.ALREADY_COMPLETE, outcome.disposition)
        assertEquals(0, outcome.bytesTransferred)
    }

    @Test
    fun `uploads a file into new folders`() {
        val local = File(Files.createTempDirectory("up").toFile(), "photo.bin")
        local.writeBytes(ByteArray(12_345) { (it % 97).toByte() })

        val outcome = connected { engine ->
            engine.upload("nested/deep/photo.bin", local.asTransferReader())
        }

        assertEquals(TransferDisposition.TRANSFERRED, outcome.disposition)
        assertEquals(12_345, outcome.bytesTransferred)
        assertArrayEquals(local.readBytes(), File(server.root, "nested/deep/photo.bin").readBytes())
    }

    @Test
    fun `resumes a partial upload`() {
        val local = File(Files.createTempDirectory("up").toFile(), "doc.bin")
        val content = ByteArray(30_000) { (it % 131).toByte() }
        local.writeBytes(content)
        // The server already has the first 10_000 bytes of it.
        server.putFile("doc.bin", 0)
        File(server.root, "doc.bin").writeBytes(content.copyOfRange(0, 10_000))

        val outcome = connected { engine ->
            engine.upload("doc.bin", local.asTransferReader())
        }

        assertEquals(TransferDisposition.RESUMED, outcome.disposition)
        assertEquals(10_000, outcome.resumeOffset)
        assertEquals(20_000, outcome.bytesTransferred)
        assertArrayEquals(content, File(server.root, "doc.bin").readBytes())
    }

    @Test
    fun `reports progress as bytes move`() {
        server.putFile("stream.bin", 100_000)
        val into = File(Files.createTempDirectory("dl").toFile(), "stream.bin")
        var lastSeen = 0L

        connected { engine ->
            engine.download(
                "stream.bin",
                into.asTransferWriter(),
                progress = { transferred, _, _ -> lastSeen = transferred },
            )
        }

        assertEquals(100_000, lastSeen)
    }
}
