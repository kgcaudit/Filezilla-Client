package org.filezilla.ftp.transfer

import org.filezilla.ftp.io.asTransferReader
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpFileOperations
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Uploading into folders the server does not have yet, against a real server.
 *
 * The failure this reproduces, in the user's words: every file failed three
 * times with `550 /HDD1/Vision/안녕/Vision/test/...: No such file or
 * directory`. Uploading a folder puts the folders a file sat in back into
 * its remote path, and nothing made them -- FTP has no "create the parents
 * too", and a server asked to `STOR` into a folder it does not have neither
 * makes it nor says which part of the path was missing.
 *
 * The planner is tested on its own; this is here because the part that was
 * wrong was never a calculation. Nothing was calling anything.
 */
class UploadIntoNewFoldersTest {

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

    private fun <T> connected(block: (FtpTransferEngine, FtpFileOperations) -> T): T {
        val settings = FtpSettings(
            host = "127.0.0.1",
            port = server.port,
            user = server.user,
            password = server.password,
            security = FtpSecurity.EXPLICIT_TLS,
            pinnedCertificate = FtpsTestServer.fingerprint,
        )
        val capabilities = ServerCapabilities()
        return FtpControlConnection(settings, capabilities).use { control ->
            control.connect()
            control.login()
            block(FtpTransferEngine(control, capabilities), FtpFileOperations(control))
        }
    }

    private fun bytes(content: String): org.filezilla.ftp.io.TransferReader =
        File.createTempFile("upload-", ".bin")
            .apply { deleteOnExit(); writeText(content) }
            .asTransferReader()

    @Test
    fun `a file uploads into a folder the server does not have`() {
        connected { engine, _ -> engine.upload("/deep/down/here/film.mkv", bytes("one")) }

        val landed = File(server.root, "deep/down/here/film.mkv")
        assertTrue(landed.isFile, "the file did not land")
        assertEquals("one", landed.readText())
    }

    /** The user's path, Korean folder names and all. */
    @Test
    fun `folder names outside ascii are made too`() {
        connected { engine, _ -> engine.upload("/Vision/안녕/Vision/test/film.mkv", bytes("two")) }

        assertEquals("two", File(server.root, "Vision/안녕/Vision/test/film.mkv").readText())
    }

    /** A second file into the same new folder does not trip over the first. */
    @Test
    fun `two files into the same new folder both land`() {
        connected { engine, _ ->
            engine.upload("/one/two/a.bin", bytes("a"))
            engine.upload("/one/two/b.bin", bytes("b"))
        }

        assertEquals("a", File(server.root, "one/two/a.bin").readText())
        assertEquals("b", File(server.root, "one/two/b.bin").readText())
    }

    /** And a folder that is already there is left exactly as it was. */
    @Test
    fun `an existing folder is not disturbed`() {
        File(server.root, "already").mkdirs()
        File(server.root, "already/kept.txt").writeText("kept")

        connected { engine, _ -> engine.upload("/already/new.txt", bytes("new")) }

        assertEquals("kept", File(server.root, "already/kept.txt").readText())
        assertEquals("new", File(server.root, "already/new.txt").readText())
    }

    /**
     * The connection is left where it was, not wherever the walk ended.
     *
     * Three cases, because only the last two catch anything. Asking whether
     * a folder is there means trying to go into it, so a probe that succeeds
     * moves the connection -- and a probe that fails does not. A path where
     * every probe fails leaves the connection where it started by accident,
     * and a test using only that one passes whether the code remembers its
     * starting point before probing or after.
     */
    @Test
    fun `the working directory survives when nothing above the file exists`() {
        File(server.root, "start").mkdirs()

        connected { engine, ops ->
            ops.changeDirectory("/start")
            engine.upload("/far/away/film.mkv", bytes("three"))
            assertEquals("/start", ops.currentDirectory())
        }
    }

    @Test
    fun `the working directory survives when part of the path exists`() {
        File(server.root, "start").mkdirs()
        File(server.root, "half").mkdirs()

        connected { engine, ops ->
            ops.changeDirectory("/start")
            engine.upload("/half/deeper/film.mkv", bytes("four"))
            assertEquals("/start", ops.currentDirectory())
        }
    }

    @Test
    fun `the working directory survives when there is nothing to make`() {
        File(server.root, "start").mkdirs()
        File(server.root, "whole").mkdirs()

        connected { engine, ops ->
            ops.changeDirectory("/start")
            engine.upload("/whole/film.mkv", bytes("five"))
            assertEquals("/start", ops.currentDirectory())
        }
    }
}
