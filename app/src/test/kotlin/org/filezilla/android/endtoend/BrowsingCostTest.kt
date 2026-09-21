package org.filezilla.android.endtoend

import org.filezilla.android.AppGraph
import org.filezilla.android.ui.PaneId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What browsing a server costs, counted in logins.
 *
 * Reported as "EUC-KR is slower than UTF-8", which it is not: the control
 * connection does strictly less work for a pinned encoding, since it skips
 * the `OPTS UTF8 ON` round trip. What differs is the server -- one of them
 * is across the internet -- and this measures what the app makes the user
 * pay for that distance.
 *
 * Measured before the connection was kept: three folders cost four logins
 * and fifty-one commands; a folder made and renamed cost three logins and
 * forty-one. Now one login and about twenty commands each.
 */
@RunWith(RobolectricTestRunner::class)
class BrowsingCostTest : AppAgainstAServer() {

    /** Commands are logged verbatim; one USER is one full login. */
    private fun logins(): Int = AppGraph.of(application).log.log.value
        .count { it.message.startsWith("USER ") }

    private fun commands(): Int = AppGraph.of(application).log.log.value
        .count { it.level == org.filezilla.ftp.protocol.LogLevel.COMMAND }

    @Test
    fun `walking three folders deep`() {
        File(onServer, "a/b/c").mkdirs()
        File(onServer, "a/one.txt").writeText("x")
        File(onServer, "a/b/two.txt").writeText("x")

        val site = savedSite()
        val model = model()

        openOnServer(model, PaneId.LEFT, site)
        val afterConnect = logins()

        model.openChild(PaneId.LEFT, "a")
        waitFor("a") { model.pane(PaneId.LEFT).path == "/a" }
        model.openChild(PaneId.LEFT, "b")
        waitFor("b") { model.pane(PaneId.LEFT).path == "/a/b" }
        model.openChild(PaneId.LEFT, "c")
        waitFor("c") { model.pane(PaneId.LEFT).path == "/a/b/c" }

        // One. Three folder taps used to be three more logins on top of
        // the one that connected: a TCP connect, a TLS handshake and six
        // commands before the LIST that was wanted, each time.
        assertEquals("connecting should cost exactly one login", 1, afterConnect)
        assertEquals(
            "walking into a folder logs in again; on a server across the " +
                "internet that is most of a second per tap",
            1,
            logins(),
        )
    }

    @Test
    fun `making a folder and renaming it`() {
        val site = savedSite()
        val model = model()
        openOnServer(model, PaneId.LEFT, site)
        val before = logins()

        model.createDirectory("made")
        waitFor("the folder") { model.pane(PaneId.LEFT).entries.any { it.name == "made" } }
        model.rename(org.filezilla.ftp.listing.DirectoryEntry(name = "made", isDirectory = true), "other")
        waitFor("the rename") { model.pane(PaneId.LEFT).entries.any { it.name == "other" } }

        assertEquals(1, before)
        assertEquals("making a folder and renaming it cost two more logins", 1, logins())
    }
}
