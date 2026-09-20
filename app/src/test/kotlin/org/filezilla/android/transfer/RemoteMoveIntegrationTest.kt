package org.filezilla.android.transfer

import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Moving a file to another folder on the same server.
 *
 * This is what cut and paste on a server has to do, and FTP has no command
 * called "move": `RNFR`/`RNTO` is a rename, and a rename whose new name is in
 * another directory is a move. Worth proving against a real server rather
 * than assuming, because a server is free to refuse a rename that crosses
 * directories and some do.
 */
class RemoteMoveIntegrationTest {

    private lateinit var server: FtpsTestServer

    @Before
    fun setUp() {
        assumeTrue(
            "FTPS test server not set up; run core-ftp/src/testFixtures/resources/ftps-server/setup.sh",
            FtpsTestServer.isAvailable,
        )
        server = FtpsTestServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private fun session(): FtpSession = FtpSession(
        FtpSettings(
            host = "127.0.0.1",
            port = server.port,
            user = server.user,
            password = server.password,
            security = FtpSecurity.EXPLICIT_TLS,
            trustAllCertificates = true,
        ),
        ServerCapabilities(),
    ).also { it.connect() }

    @Test
    fun `a file moves between two folders`() {
        server.putFile("from/film.mkv", 2_048)
        File(server.root, "to").mkdirs()

        session().use { it.rename("/from/film.mkv", "/to/film.mkv") }

        assertFalse(File(server.root, "from/film.mkv").exists())
        assertTrue(File(server.root, "to/film.mkv").isFile)
    }

    @Test
    fun `a folder moves with what is in it`() {
        server.putFile("from/season/ep1.mkv", 512)
        File(server.root, "to").mkdirs()

        session().use { it.rename("/from/season", "/to/season") }

        assertFalse(File(server.root, "from/season").exists())
        assertTrue(File(server.root, "to/season/ep1.mkv").isFile)
    }

    /** Renaming in place is the same command, and still has to work. */
    @Test
    fun `a file is renamed where it stands`() {
        server.putFile("pub/before.bin", 64)

        session().use { it.rename("/pub/before.bin", "/pub/after.bin") }

        assertTrue(File(server.root, "pub/after.bin").isFile)
    }
}
