package org.filezilla.android.ui

import org.filezilla.android.data.SiteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a pane is, told apart from what it is showing.
 *
 * Small, and worth having because the distinction it draws used to be a null
 * check. "No server chosen yet" and "this pane shows the phone" were the same
 * absent site, so keying anything on that site answered the same for both --
 * which is how every local pane came to announce that it was not connected.
 */
class PaneTest {

    private val site = SiteEntity(
        id = "s1",
        name = "NAS",
        host = "nas.example",
        port = 21,
        user = "u",
        passwordCipher = "x",
        security = "PLAIN",
        transferMode = "DEFAULT",
        pinnedCertificate = null,
        initialPath = null,
    )

    @Test
    fun `a local pane has no site and is not empty`() {
        val state = BrowseState(source = PaneSource.Local)

        assertNull(state.site)
        assertTrue(state.isLocal)
    }

    @Test
    fun `a remote pane carries its site`() {
        val state = BrowseState(source = PaneSource.Remote(site))

        assertEquals(site, state.site)
        assertFalse(state.isLocal)
    }

    /**
     * The one that mattered: an empty pane and a local pane both have no
     * site, and only one of them should be asking for a server.
     */
    @Test
    fun `an empty pane and a local pane are not the same absent site`() {
        val empty = BrowseState(source = PaneSource.Empty)
        val local = BrowseState(source = PaneSource.Local)

        assertNull(empty.site)
        assertNull(local.site)
        // Same site, different panes. Anything deciding on the site alone
        // cannot tell these apart, which is the bug this guards.
        assertFalse(empty.isLocal)
        assertTrue(local.isLocal)
    }

    // ----------------------------------------------------------- the layout

    /**
     * Left on the phone, right on the server: the arrangement the whole
     * screen exists for -- what you have on one side, what you are sending it
     * to on the other.
     */
    @Test
    fun `the panes start on opposite kinds of place`() {
        assertEquals(PaneSource.Local, defaultSourceFor(PaneId.LEFT))
        assertEquals(PaneSource.Empty, defaultSourceFor(PaneId.RIGHT))
    }

    /**
     * The pager's page and the pane have to agree in both directions, or the
     * toolbar acts on the pane the user cannot see.
     */
    @Test
    fun `a page and a pane map to each other`() {
        for (id in PaneId.entries) {
            assertEquals(id, paneAt(pageOf(id)))
        }
        assertEquals(PaneId.LEFT, paneAt(0))
        assertEquals(PaneId.RIGHT, paneAt(1))
    }

    // -------------------------------------------------- one pane or two

    @Test
    fun `a phone shows one pane at a time`() {
        // A tall phone is around 360-430dp wide; a wide one in landscape is
        // still short of room for two readable listings.
        assertFalse(showsBothPanes(360))
        assertFalse(showsBothPanes(430))
        assertFalse(showsBothPanes(SIDE_BY_SIDE_WIDTH_DP - 1))
    }

    @Test
    fun `an opened foldable or a tablet shows both`() {
        assertTrue(showsBothPanes(SIDE_BY_SIDE_WIDTH_DP))
        // A Fold opened out, and a tablet.
        assertTrue(showsBothPanes(840))
        assertTrue(showsBothPanes(1280))
    }

    /**
     * Folding the device back up has to put it back to one pane, so the
     * question is asked of the width every time rather than answered once.
     */
    @Test
    fun `the answer follows the width in both directions`() {
        assertTrue(showsBothPanes(800))
        assertFalse(showsBothPanes(400))
    }
}
