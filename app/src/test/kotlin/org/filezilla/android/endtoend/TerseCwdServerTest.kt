package org.filezilla.android.endtoend

import org.filezilla.android.AppGraph
import org.filezilla.android.ui.PaneId
import org.filezilla.ftp.protocol.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A server that does not name the directory in its `CWD` reply.
 *
 * Opening a folder asks the server where it landed, because the path asked
 * for may have been relative or a symbolic link. `PWD` answers reliably and
 * costs a round trip; many servers have already answered in the `CWD` reply
 * and the app now takes it there, which is a quarter off the cost of every
 * folder opened.
 *
 * vsftpd and ProFTPD have not. They reply "Directory successfully changed"
 * and nothing more, and a client that assumed otherwise would be building
 * its next path on nothing -- so the fallback is not an edge case, it is
 * two of the three servers in the compatibility matrix. This is that half
 * of the branch, against a server told to answer their way.
 */
@RunWith(RobolectricTestRunner::class)
class TerseCwdServerTest : AppAgainstAServer() {

    override val cwdEchoesPath: Boolean get() = false

    private fun commands(): List<String> = AppGraph.of(application).log.log.value
        .filter { it.level == LogLevel.COMMAND }
        .map { it.message }

    @Test
    fun `the app asks where it is when the server will not say`() {
        File(onServer, "영화/한국").mkdirs()
        File(onServer, "영화/한국/자막.srt").writeText("x")

        val site = savedSite()
        val model = model()
        openOnServer(model, PaneId.LEFT, site)

        val before = commands().size
        model.openChild(PaneId.LEFT, "영화")
        waitFor("the folder") { model.pane(PaneId.LEFT).path == "/영화" }

        val sent = commands().drop(before)
        assertTrue(
            "the app took a terse reply for an answer and never asked: $sent",
            sent.any { it == "PWD" },
        )
        assertEquals("/영화", model.pane(PaneId.LEFT).path)
    }

    /** And keeps working all the way down, which is what the path is for. */
    @Test
    fun `walking deeper still lands in the right place`() {
        File(onServer, "영화/한국").mkdirs()
        File(onServer, "영화/한국/자막.srt").writeText("x")

        val site = savedSite()
        val model = model()
        openOnServer(model, PaneId.LEFT, site)

        model.openChild(PaneId.LEFT, "영화")
        waitFor("one") { model.pane(PaneId.LEFT).path == "/영화" }
        model.openChild(PaneId.LEFT, "한국")
        waitFor("two") { model.pane(PaneId.LEFT).path == "/영화/한국" }

        assertEquals(listOf("자막.srt"), model.pane(PaneId.LEFT).entries.map { it.name })
    }
}
