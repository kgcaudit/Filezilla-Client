package org.filezilla.android.endtoend

import org.filezilla.android.AppGraph
import org.filezilla.android.ui.PaneId
import org.filezilla.ftp.protocol.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What browsing a server costs, counted rather than guessed at.
 *
 * Reported as "EUC-KR feels slower than UTF-8", which it is not: a pinned
 * encoding skips the `OPTS UTF8 ON` round trip, so the control connection
 * does strictly less work for it. What differed was which server was on
 * the end -- one of them across the internet -- and that exposed how much
 * the app was charging for the distance.
 *
 * Round trips are the currency. On a server on the same desk each is a few
 * tens of milliseconds and nobody notices; on a NAS across the internet
 * each is most of a tenth of a second, and they are paid every time a
 * folder is tapped.
 *
 * Measured before any of this: three folders cost four logins and
 * fifty-one commands, because every browse opened a whole connection and
 * hung up. Now one login, and three commands a folder.
 */
@RunWith(RobolectricTestRunner::class)
class BrowsingCostTest : AppAgainstAServer() {

    /** Commands are logged verbatim; one USER is one full login. */
    private fun logins(): Int = AppGraph.of(application).log.log.value
        .count { it.message.startsWith("USER ") }

    private fun commands(): Int = AppGraph.of(application).log.log.value
        .count { it.level == LogLevel.COMMAND }

    @Test
    fun `walking three folders deep`() {
        File(onServer, "a/b/c").mkdirs()
        File(onServer, "a/one.txt").writeText("x")

        val site = savedSite()
        val model = model()

        openOnServer(model, PaneId.LEFT, site)
        val loginsAfterConnect = logins()
        val commandsAfterConnect = commands()

        model.openChild(PaneId.LEFT, "a")
        waitFor("a") { model.pane(PaneId.LEFT).path == "/a" }
        model.openChild(PaneId.LEFT, "b")
        waitFor("b") { model.pane(PaneId.LEFT).path == "/a/b" }
        model.openChild(PaneId.LEFT, "c")
        waitFor("c") { model.pane(PaneId.LEFT).path == "/a/b/c" }

        // One login for the lot. Three taps used to be three more on top of
        // the one that connected: a TCP connect, a TLS handshake and six
        // commands before the listing that was wanted, each time.
        assertEquals("connecting should cost exactly one login", 1, loginsAfterConnect)
        assertEquals(
            "walking into a folder logs in again; across the internet that " +
                "is most of a second a tap",
            1,
            logins(),
        )

        // CWD, PASV, MLSD. The PWD that used to follow every CWD is
        // answered by the CWD reply itself on a server that names the
        // path, which this one does -- see CwdReply. Servers that do not
        // are covered by TerseCwdServerTest.
        assertEquals(
            "a folder tap sends more than it needs to",
            9,
            commands() - commandsAfterConnect,
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
        model.rename(
            org.filezilla.ftp.listing.DirectoryEntry(name = "made", isDirectory = true),
            "other",
        )
        waitFor("the rename") { model.pane(PaneId.LEFT).entries.any { it.name == "other" } }

        assertEquals(1, before)
        assertEquals("making a folder and renaming it cost two more logins", 1, logins())
    }
}
