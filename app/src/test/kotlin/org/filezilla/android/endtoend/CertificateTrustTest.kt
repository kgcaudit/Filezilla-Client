package org.filezilla.android.endtoend

import org.filezilla.android.AppGraph
import org.filezilla.android.ui.PaneId
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Meeting a server for the first time, with the whole app in the loop.
 *
 * The thing this replaces was a switch called "accept any certificate".
 * Every home server's certificate is signed by nobody, so the switch was
 * not really a choice -- it was the price of connecting at all -- and
 * having paid it once, that server's connection stayed encrypted but
 * stopped being addressed to anyone in particular. On somebody else's
 * network the password went to whoever answered.
 *
 * So the question is asked instead of switched off, and this is the whole
 * path: refused, asked, recognised, connected, remembered.
 */
@RunWith(RobolectricTestRunner::class)
class CertificateTrustTest : AppAgainstAServer() {

    /** A site with nothing accepted yet, which is how a new one starts. */
    private fun unknownSite() = savedSite().let { site ->
        val fresh = site.copy(pinnedCertificate = null)
        kotlinx.coroutines.runBlocking {
            AppGraph.of(application).database.sites().upsert(fresh)
        }
        fresh
    }

    private fun savedPin(id: String) = kotlinx.coroutines.runBlocking {
        AppGraph.of(application).database.sites().byId(id)?.pinnedCertificate
    }

    @Test
    fun `an unrecognised server is not connected to, it is asked about`() {
        File(onServer, "secret.txt").writeText("x")

        val model = model()
        model.showSite(PaneId.LEFT, unknownSite())
        waitFor("the question") { model.certificateQuestion != null }

        val question = model.certificateQuestion!!
        assertEquals(
            "the fingerprint shown has to be the one the server presented",
            FtpsTestServer.fingerprint,
            question.certificate.fingerprint,
        )
        assertTrue("a home server signs its own", question.certificate.selfSigned)
        assertNull("nothing was accepted before, so nothing changed", question.replacing)

        // And nothing was listed. Asking after showing the files would be
        // asking about a decision already taken.
        assertTrue(model.pane(PaneId.LEFT).entries.isEmpty())
    }

    @Test
    fun `recognising it connects, and it is not asked again`() {
        File(onServer, "one.txt").writeText("x")

        val model = model()
        val site = unknownSite()
        model.showSite(PaneId.LEFT, site)
        waitFor("the question") { model.certificateQuestion != null }

        model.trustCertificate()
        waitFor("the listing") {
            model.pane(PaneId.LEFT).entries.any { it.name == "one.txt" }
        }

        assertNull("the question should be gone", model.certificateQuestion)
        assertEquals(
            "the fingerprint has to be saved, or the next run asks again",
            FtpsTestServer.fingerprint,
            savedPin(site.id),
        )

        // A second pane onto the same server goes straight through: the
        // question was about the server, not about that one pane.
        val pinned = kotlinx.coroutines.runBlocking {
            AppGraph.of(application).database.sites().byId(site.id)!!
        }
        model.showSite(PaneId.RIGHT, pinned)
        waitFor("the second pane") { !model.pane(PaneId.RIGHT).loading }
        assertNull(model.certificateQuestion)
        assertTrue(model.pane(PaneId.RIGHT).entries.any { it.name == "one.txt" })
    }

    @Test
    fun `declining leaves the server alone`() {
        val model = model()
        val site = unknownSite()
        model.showSite(PaneId.LEFT, site)
        waitFor("the question") { model.certificateQuestion != null }

        model.dismissCertificateQuestion()

        assertNull(model.certificateQuestion)
        assertNull("saying no must not pin anything", savedPin(site.id))
        assertTrue(model.pane(PaneId.LEFT).entries.isEmpty())
        // The pane says why rather than sitting blank.
        assertNotNull(model.pane(PaneId.LEFT).error)
    }

    @Test
    fun `a certificate that is not the one accepted is reported as a change`() {
        val model = model()
        // A server that was trusted, now presenting something else. Which is
        // both what a renewal looks like and what somebody standing in the
        // middle looks like -- the app cannot tell, so it must not guess.
        val wrongPin = FtpsTestServer.fingerprint.replaceFirst("A", "B")
            .let { if (it == FtpsTestServer.fingerprint) it.replaceFirst("1", "2") else it }
        val site = savedSite().copy(pinnedCertificate = wrongPin)
        kotlinx.coroutines.runBlocking {
            AppGraph.of(application).database.sites().upsert(site)
        }

        model.showSite(PaneId.LEFT, site)
        waitFor("the warning") { model.certificateQuestion != null }

        val question = model.certificateQuestion!!
        assertEquals(
            "the dialog has to say what was accepted before, or there is nothing to compare",
            wrongPin,
            question.replacing,
        )
        assertEquals(FtpsTestServer.fingerprint, question.certificate.fingerprint)
        assertFalse("the old pin must still be saved until it is replaced", savedPin(site.id) == null)
    }

    @Test
    fun `a site saved with a pin connects with no question at all`() {
        File(onServer, "one.txt").writeText("x")

        val model = model()
        // savedSite() pins the test server, which is what every other test
        // here relies on -- so this also guards those from silently
        // starting to ask.
        openOnServer(model, PaneId.LEFT, savedSite())

        assertNull(model.certificateQuestion)
        assertTrue(model.pane(PaneId.LEFT).entries.any { it.name == "one.txt" })
    }
}
