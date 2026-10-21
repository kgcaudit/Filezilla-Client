package org.filezilla.android.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.filezilla.android.R
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.data.SiteProtocol
import org.filezilla.android.ui.theme.OloTheme
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.TransferMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The saved-servers list, rendered and tapped the way the sites tab drives it.
 *
 * A server of any protocol lives in this one list, so the list is where a
 * mislabelled or mis-wired row would show: the test puts an SMB and a WebDAV
 * server in it and checks the card names each one and its address, that a tap
 * on the card connects *that* server and the row's two buttons edit and delete
 * *that* server, and that reordering is offered only when there is more than
 * one row and then moves the row it belongs to. The editor the list can open is
 * not exercised here -- its dropdowns never settle under Robolectric, and it
 * has its own home -- so every case leaves `editing` null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class SitesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun text(resId: Int, vararg args: Any) = app.getString(resId, *args)

    private fun site(
        id: String,
        name: String,
        host: String,
        port: Int,
        user: String,
        protocol: SiteProtocol,
        security: FtpSecurity = FtpSecurity.PLAIN,
        smbShare: String? = null,
    ) = SiteEntity(
        id = id,
        name = name,
        host = host,
        port = port,
        user = user,
        passwordCipher = "",
        security = security.name,
        transferMode = TransferMode.DEFAULT.name,
        protocol = protocol.name,
        initialPath = null,
        smbShare = smbShare,
        smbDomain = null,
    )

    private val nas = site("1", "Office NAS", "nas.local", 445, "bob", SiteProtocol.SMB, smbShare = "Public")
    private val dav = site("2", "Cloud", "dav.example.org", 443, "ann", SiteProtocol.WEBDAV, FtpSecurity.IMPLICIT_TLS)

    private class Recorder {
        val connected = mutableListOf<SiteEntity>()
        val edited = mutableListOf<SiteEntity?>()
        val deleted = mutableListOf<SiteEntity>()
        val moved = mutableListOf<Pair<SiteEntity, Move>>()
    }

    private fun show(sites: List<SiteEntity>): Recorder {
        val rec = Recorder()
        compose.setContent {
            OloTheme {
                SitesScreen(
                    sites = sites,
                    editing = null,
                    onEdit = { rec.edited += it },
                    onSave = {},
                    onTest = { SiteTestResult.Success },
                    onDelete = { rec.deleted += it },
                    onConnect = { rec.connected += it },
                    onMove = { s, m -> rec.moved += s to m },
                )
            }
        }
        compose.waitForIdle()
        return rec
    }

    @Test
    fun `an empty list shows the empty state`() {
        show(emptyList())
        compose.onNodeWithText(text(R.string.sites_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `each server card names the server and its address`() {
        show(listOf(nas, dav))

        compose.onNodeWithText("Office NAS").assertIsDisplayed()
        compose.onNodeWithText("bob@nas.local:445").assertIsDisplayed()
        compose.onNodeWithText("Cloud").assertIsDisplayed()
        compose.onNodeWithText("ann@dav.example.org:443").assertIsDisplayed()
    }

    @Test
    fun `each protocol names its security in its own words`() {
        val ftp = site("3", "Archive", "ftp.example.org", 21, "cat", SiteProtocol.FTP, FtpSecurity.PLAIN)
        val ftps = site("4", "Vault", "ftps.example.org", 990, "dog", SiteProtocol.FTP, FtpSecurity.IMPLICIT_TLS)
        val sftp = site("5", "Shell", "sftp.example.org", 22, "eve", SiteProtocol.SFTP)
        val davPlain = site("6", "Intranet", "dav.local", 80, "foo", SiteProtocol.WEBDAV, FtpSecurity.PLAIN)
        show(listOf(ftp, ftps, sftp, davPlain, dav, nas))

        // FTP keeps the FTPS-mode wording it always had.
        compose.onNodeWithText(text(R.string.security_plain)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.security_implicit)).assertIsDisplayed()
        // SFTP reads as the encrypted protocol it is, not an FTP mode.
        compose.onNodeWithText(text(R.string.protocol_sftp)).assertIsDisplayed()
        // WebDAV reads as http/https, not FTPS.
        compose.onNodeWithText(text(R.string.webdav_http)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.webdav_https)).assertIsDisplayed()
        // SMB names its share rather than claiming an FTP security grade.
        compose.onNodeWithText("Public").assertIsDisplayed()
    }

    @Test
    fun `a non-FTP server is not labelled with FTP vocabulary`() {
        // The WebDAV-over-https and SMB servers both carry a security value
        // (IMPLICIT_TLS, PLAIN) that the old shared label would have printed as
        // "FTPS implicit" and "Plain FTP" -- the bug this screen had.
        show(listOf(dav, nas))

        compose.onNodeWithText(text(R.string.security_implicit)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.security_plain)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.security_explicit)).assertDoesNotExist()
    }

    @Test
    fun `an SMB server without a share omits the security line`() {
        // A site saved before the share column existed keeps its share in the
        // path, so the card has no share to name -- and must still not fall
        // back to an FTP security label.
        val legacy = site("7", "Legacy NAS", "old.local", 445, "bob", SiteProtocol.SMB, smbShare = null)
        show(listOf(legacy))

        compose.onNodeWithText("Legacy NAS").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.security_plain)).assertDoesNotExist()
    }

    @Test
    fun `tapping a card connects to that server`() {
        val rec = show(listOf(nas, dav))

        compose.onNodeWithText("Cloud").performClick()
        compose.waitForIdle()

        assertEquals(listOf(dav), rec.connected)
    }

    @Test
    fun `the edit and delete buttons target their own row`() {
        val rec = show(listOf(nas, dav))

        compose.onNodeWithContentDescription(text(R.string.sites_edit, "Office NAS")).performClick()
        compose.onNodeWithContentDescription(text(R.string.sites_delete, "Cloud")).performClick()
        compose.waitForIdle()

        assertEquals(listOf<SiteEntity?>(nas), rec.edited)
        assertEquals(listOf(dav), rec.deleted)
    }

    @Test
    fun `a single server offers no reordering`() {
        show(listOf(nas))
        compose.onNodeWithContentDescription(text(R.string.sites_move_up, "Office NAS")).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.sites_move_down, "Office NAS")).assertDoesNotExist()
    }

    @Test
    fun `moving a row down reports that row and the direction`() {
        val rec = show(listOf(nas, dav))

        // The first row can go down but not up; the arrow it offers moves it.
        compose.onNodeWithContentDescription(text(R.string.sites_move_down, "Office NAS")).performClick()
        compose.waitForIdle()

        assertEquals(listOf(nas to Move.DOWN), rec.moved)
    }
}
