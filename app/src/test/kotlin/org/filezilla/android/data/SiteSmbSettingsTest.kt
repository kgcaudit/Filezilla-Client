package org.filezilla.android.data

import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.TransferMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SiteEntity.toSmbSettings], and in particular that it reads the new dedicated
 * share and domain columns but still falls back to the old convention -- share
 * as the first path segment, domain as `DOMAIN\user` -- for a site saved before
 * those columns existed. That fallback is what keeps a saved SMB site working
 * across the version 11 migration, so it is pinned.
 */
class SiteSmbSettingsTest {

    private val passwords = FakePasswordCipher()

    private fun site(
        user: String,
        initialPath: String?,
        smbShare: String? = null,
        smbDomain: String? = null,
    ) = SiteEntity(
        id = "s",
        name = "NAS",
        host = "nas.local",
        port = 445,
        user = user,
        passwordCipher = passwords.encrypt("secret"),
        security = FtpSecurity.PLAIN.name,
        transferMode = TransferMode.DEFAULT.name,
        protocol = SiteProtocol.SMB.name,
        initialPath = initialPath,
        smbShare = smbShare,
        smbDomain = smbDomain,
    )

    @Test
    fun `dedicated share and domain are used as given`() {
        val s = site(user = "alice", initialPath = "/docs", smbShare = "Public", smbDomain = "WORKGROUP")
            .toSmbSettings(passwords)
        assertEquals("Public", s.share)
        assertEquals("/docs", s.basePath)
        assertEquals("WORKGROUP", s.domain)
        assertEquals("alice", s.user)
        assertEquals("secret", s.password)
    }

    @Test
    fun `a dedicated share with no folder maps the root`() {
        val s = site(user = "alice", initialPath = null, smbShare = "Public").toSmbSettings(passwords)
        assertEquals("Public", s.share)
        assertEquals("/", s.basePath)
        assertNull(s.domain)
        assertEquals("alice", s.user)
    }

    @Test
    fun `a legacy site reads the share from the path and the domain from the user`() {
        val s = site(user = "WORKGROUP\\bob", initialPath = "/Public/docs").toSmbSettings(passwords)
        assertEquals("Public", s.share)
        assertEquals("/docs", s.basePath)
        assertEquals("WORKGROUP", s.domain)
        assertEquals("bob", s.user)
    }

    @Test
    fun `a legacy site without a domain or subfolder still resolves`() {
        val s = site(user = "bob", initialPath = "/Public").toSmbSettings(passwords)
        assertEquals("Public", s.share)
        assertEquals("/", s.basePath)
        assertNull(s.domain)
        assertEquals("bob", s.user)
    }
}
