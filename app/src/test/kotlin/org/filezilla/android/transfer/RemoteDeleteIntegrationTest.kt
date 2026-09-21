package org.filezilla.android.transfer

import org.filezilla.android.ui.RemoteDelete
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
 * Deleting a folder on a real server.
 *
 * The user's report, exactly: "서버 내 폴더 삭제가 안 돼, 파일 삭제는 가능" --
 * the server answered `550 test: Directory not empty`. FTP has no recursive
 * delete; `RMD` refuses anything that is not already empty.
 *
 * A planner test can show the commands come out in the right order, and one
 * does. What it cannot show is that a real server accepts them -- that the
 * absolute paths are the ones it expects, that the walk leaves the connection
 * somewhere usable, that `RMD` succeeds once the walk has emptied the folder.
 * That is what this is for, and it is the failure the user actually saw.
 */
class RemoteDeleteIntegrationTest {

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
            pinnedCertificate = FtpsTestServer.fingerprint,
        ),
        ServerCapabilities(),
    ).also { it.connect() }

    /** Runs the app's own removal, the way the view model runs it. */
    private fun remove(directory: String, names: List<String>) {
        session().use { session ->
            session.changeDirectory(directory)
            val picks = session.list().filter { it.name in names }
            val plan = RemoteDelete.plan(
                lister = { path ->
                    session.changeDirectory(path)
                    session.list()
                },
                directory = directory,
                picks = picks,
            )
            assertFalse("the walk did not finish", plan.truncated)
            for (step in plan.steps) {
                if (step.isDirectory) session.removeDirectory(step.path) else session.deleteFile(step.path)
            }
            // The pane re-lists from here, so the walk must not leave the
            // connection inside a folder it has just removed.
            session.changeDirectory(directory)
            session.list()
        }
    }

    @Test
    fun `a folder with files in it is deleted`() {
        server.putFile("test/one.bin", 512)
        server.putFile("test/two.bin", 256)

        remove("/", listOf("test"))

        assertFalse(File(server.root, "test").exists())
    }

    @Test
    fun `a folder of folders is deleted from the bottom up`() {
        server.putFile("vision/hdd1/database/variety/episode.bin", 1_024)
        server.putFile("vision/loose.bin", 16)

        remove("/", listOf("vision"))

        assertFalse(File(server.root, "vision").exists())
    }

    /** Deleting several picked rows at once, folders and files mixed. */
    @Test
    fun `a selection of both kinds is deleted`() {
        server.putFile("keep/mine.bin", 32)
        server.putFile("drop/a.bin", 32)
        server.putFile("loose.bin", 32)

        remove("/", listOf("drop", "loose.bin"))

        assertFalse(File(server.root, "drop").exists())
        assertFalse(File(server.root, "loose.bin").exists())
        assertTrue("an unpicked folder was taken too", File(server.root, "keep/mine.bin").exists())
    }

    /** An empty folder still goes, which is the one case RMD could always do. */
    @Test
    fun `an empty folder is deleted`() {
        File(server.root, "hollow").mkdirs()

        remove("/", listOf("hollow"))

        assertFalse(File(server.root, "hollow").exists())
    }

    /** Deleting inside a subdirectory, rather than at the server's root. */
    @Test
    fun `a folder below the root is deleted`() {
        server.putFile("hdd1/vision/test/a.bin", 64)
        server.putFile("hdd1/vision/keep.bin", 64)

        remove("/hdd1/vision", listOf("test"))

        assertFalse(File(server.root, "hdd1/vision/test").exists())
        assertTrue(File(server.root, "hdd1/vision/keep.bin").exists())
    }
}
