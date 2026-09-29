package org.filezilla.android.transfer

import kotlinx.coroutines.runBlocking
import org.filezilla.android.data.FakePasswordCipher
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.data.SiteProtocol
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.sftp.SftpTestServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * That a site marked SFTP is browsed over SFTP.
 *
 * The engine itself is covered against a live server in :core-ftp; this proves
 * the wiring above it -- that [BrowseConnections.forServers] opens an
 * [SftpSession] for an SFTP site, that the pinned host key reaches it, and that
 * a change made through it is a change [TransferManager.browse] would notice.
 */
class SftpBrowseWiringTest {

    private lateinit var server: SftpTestServer
    private val passwords = FakePasswordCipher()

    @Before
    fun setUp() {
        server = SftpTestServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private fun sftpSite(): SiteEntity = SiteEntity(
        id = "sftp-1",
        name = "SFTP box",
        host = "127.0.0.1",
        port = server.port,
        user = server.user,
        passwordCipher = passwords.encrypt(server.password),
        security = "PLAIN",
        transferMode = "DEFAULT",
        protocol = SiteProtocol.SFTP.name,
        knownHostKey = server.discoverHostKey(),
        initialPath = null,
    )

    private fun pool() = BrowseConnections.forServers(ServerCapabilities(), passwords)

    @Test
    fun `an SFTP site opens an SFTP session`() = runBlocking {
        val kind = pool().withSession(sftpSite()) { it::class.simpleName }
        assertEquals("SftpSession", kind)
    }

    @Test
    fun `lists a directory over SFTP`() {
        server.putFile("report.txt", 321)
        File(server.root, "folder").mkdirs()

        val entries = runBlocking { pool().withSession(sftpSite()) { it.list() } }

        assertEquals(321, entries.first { it.name == "report.txt" }.size)
        assertTrue(entries.first { it.name == "folder" }.isDirectory)
    }

    @Test
    fun `makes renames and removes over SFTP, and each is a recorded write`() {
        server.putFile("before.txt", 10)

        runBlocking {
            pool().withSession(sftpSite()) { session ->
                val start = session.writes
                session.createDirectory("made")
                session.rename("before.txt", "after.txt")
                session.changeMode("after.txt", "640")
                session.deleteFile("after.txt")
                session.removeDirectory("made")
                // Each change is counted, so TransferManager.browse drops its
                // cached listing exactly as it does for FTP.
                assertEquals(start + 5, session.writes)

                val names = session.list().map { it.name }
                assertTrue("after.txt" !in names)
                assertTrue("made" !in names)
            }
        }
    }

    @Test
    fun `a wrong host key is refused rather than trusted`() {
        val site = sftpSite().copy(knownHostKey = "SHA256:not-the-real-key")
        val refused = runCatching { runBlocking { pool().withSession(site) { it.list() } } }
        assertNull("a mismatched host key must not connect", refused.getOrNull())
        assertTrue(refused.exceptionOrNull() is org.filezilla.ftp.sftp.HostKeyNotTrusted)
    }
}
