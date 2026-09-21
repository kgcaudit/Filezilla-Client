package org.filezilla.ftp.transfer

import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.protocol.Capability
import org.filezilla.ftp.protocol.CapabilityName
import org.filezilla.ftp.protocol.FtpCommandException
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpFileOperations
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** Directory listing and file management against a live FTPS server. */
class DirectoryIntegrationTest {

    private lateinit var server: FtpsTestServer

    @BeforeEach
    fun setUp() {
        assumeTrue(
            FtpsTestServer.isAvailable,
            "FTPS test server not set up; run core-ftp/src/test/resources/ftps-server/setup.sh",
        )
        server = FtpsTestServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private fun <T> connected(
        capabilities: ServerCapabilities = ServerCapabilities(),
        block: (FtpControlConnection, FtpTransferEngine, FtpFileOperations) -> T,
    ): T {
        val settings = FtpSettings(
            host = "127.0.0.1",
            port = server.port,
            user = server.user,
            password = server.password,
            security = FtpSecurity.EXPLICIT_TLS,
            pinnedCertificate = FtpsTestServer.fingerprint,
        )
        return FtpControlConnection(settings, capabilities).use { control ->
            control.connect()
            control.login()
            block(control, FtpTransferEngine(control, capabilities), FtpFileOperations(control))
        }
    }

    private fun List<DirectoryEntry>.byName(name: String) = firstOrNull { it.name == name }

    @Test
    fun `lists files and directories with sizes`() {
        server.putFile("alpha.bin", 1234)
        server.putFile("beta.bin", 99)
        File(server.root, "subdir").mkdirs()

        val entries = connected { _, engine, _ -> engine.list() }

        val alpha = entries.byName("alpha.bin")!!
        assertEquals(1234, alpha.size)
        assertFalse(alpha.isDirectory)

        assertEquals(99, entries.byName("beta.bin")!!.size)

        val sub = entries.byName("subdir")!!
        assertTrue(sub.isDirectory)

        // The current and parent directory entries are never listed.
        assertNull(entries.byName("."))
        assertNull(entries.byName(".."))
    }

    @Test
    fun `uses MLSD when the server advertises it`() {
        server.putFile("m.bin", 10)
        val capabilities = ServerCapabilities()
        val entries = connected(capabilities) { _, engine, _ -> engine.list() }

        // pyftpdlib advertises MLST in FEAT, so the engine should have taken
        // the exact path rather than parsing human-readable LIST output.
        assertEquals(
            Capability.YES,
            capabilities.get(
                ServerCapabilities.ServerKey("127.0.0.1", server.port, server.user),
                CapabilityName.MLSD_COMMAND,
            ),
        )
        val entry = entries.byName("m.bin")!!
        assertEquals(10, entry.size)
        // MLSD timestamps are UTC to the second, so no MDTM round trip is needed.
        assertTrue(entry.hasTime)
    }

    @Test
    fun `handles filenames with spaces and unicode`() {
        server.putFile("a file with spaces.txt", 5)
        server.putFile("한글 파일.txt", 7)

        val entries = connected { _, engine, _ -> engine.list() }

        assertEquals(5, entries.byName("a file with spaces.txt")!!.size)
        assertEquals(7, entries.byName("한글 파일.txt")!!.size)
    }

    @Test
    fun `navigates directories`() {
        File(server.root, "pub/inner").mkdirs()
        server.putFile("pub/inner/deep.bin", 3)

        val names = connected { _, engine, ops ->
            val root = ops.currentDirectory()
            assertEquals("/", root)

            ops.changeDirectory("pub/inner")
            assertEquals("/pub/inner", ops.currentDirectory())
            val inner = engine.list().map { it.name }

            ops.changeToParentDirectory()
            assertEquals("/pub", ops.currentDirectory())
            inner
        }
        assertEquals(listOf("deep.bin"), names)
    }

    @Test
    fun `creates removes renames and deletes`() {
        server.putFile("old.bin", 4)

        connected { _, engine, ops ->
            ops.createDirectory("newdir")
            assertTrue(engine.list().byName("newdir")!!.isDirectory)

            ops.rename("old.bin", "new.bin")
            val afterRename = engine.list()
            assertNull(afterRename.byName("old.bin"))
            assertEquals(4, afterRename.byName("new.bin")!!.size)

            ops.deleteFile("new.bin")
            ops.removeDirectory("newdir")

            val afterDelete = engine.list()
            assertNull(afterDelete.byName("new.bin"))
            assertNull(afterDelete.byName("newdir"))
        }
    }

    @Test
    fun `reports a failed operation instead of pretending it worked`() {
        connected { _, _, ops ->
            val failure = assertThrows<FtpCommandException> { ops.changeDirectory("does-not-exist") }
            assertEquals(5, failure.reply.category)

            assertThrows<FtpCommandException> { ops.deleteFile("not-there.bin") }
        }
    }

    @Test
    fun `lists an empty directory as empty`() {
        File(server.root, "empty").mkdirs()
        val entries = connected { _, engine, ops ->
            ops.changeDirectory("empty")
            engine.list()
        }
        assertTrue(entries.isEmpty(), "expected no entries but got $entries")
    }
}
