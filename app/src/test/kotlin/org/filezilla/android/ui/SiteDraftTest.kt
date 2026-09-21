package org.filezilla.android.ui

import org.filezilla.android.data.FakePasswordCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a blank credential field means when the form is saved.
 *
 * The new-server form used to arrive with "anonymous" and "anonymous@"
 * already typed into it, so anyone entering a real account had to delete them
 * first. They are hints now, and the substitution happens here instead --
 * which moves the rule somewhere it can be wrong quietly, so it is pinned.
 */
class SiteDraftTest {

    private val passwords = FakePasswordCipher()

    private fun saved(user: String, password: String): Pair<String, String> {
        val entity = SiteDraft.blank()
            .copy(host = "ftp.example.org", user = user, password = password)
            .toEntity(passwords)
        return entity.user to passwords.decrypt(entity.passwordCipher).orEmpty()
    }

    @Test
    fun `a new server starts with both credential fields empty`() {
        val blank = SiteDraft.blank()
        assertEquals("", blank.user)
        assertEquals("", blank.password)
    }

    @Test
    fun `leaving both blank saves the anonymous convention`() {
        assertEquals(
            SiteDraft.ANONYMOUS_USER to SiteDraft.ANONYMOUS_PASSWORD,
            saved(user = "", password = ""),
        )
    }

    @Test
    fun `a typed account is saved exactly as typed`() {
        assertEquals("kolon" to "s3cret", saved(user = "kolon", password = "s3cret"))
    }

    @Test
    fun `a named account with no password keeps the empty password`() {
        // Some servers allow it, and quietly substituting the anonymous
        // password would change what the user asked for into a login that
        // fails for a reason the log would not explain.
        assertEquals("kolon" to "", saved(user = "kolon", password = ""))
    }

    @Test
    fun `a blank name falls back to the host`() {
        val entity = SiteDraft.blank().copy(host = "ftp.example.org").toEntity(passwords)
        assertEquals("ftp.example.org", entity.name)
    }
    @Test
    fun `editing a site keeps the certificate that was accepted for it`() {
        // Nothing in the editor sets this, so it is exactly the field a
        // round trip through a form drops without anyone noticing -- and
        // dropping it means the server starts asking to be recognised
        // again every time its port or its name is corrected.
        val pin = "AA:BB:CC:DD"
        val saved = SiteDraft.blank()
            .copy(host = "nas.home", pinnedCertificate = pin)
            .toEntity(FakePasswordCipher())

        val edited = SiteDraft.of(saved, FakePasswordCipher())
            .copy(name = "renamed")
            .toEntity(FakePasswordCipher())

        assertEquals(pin, edited.pinnedCertificate)
    }

    @Test
    fun `a new site starts with nothing accepted`() {
        assertNull(SiteDraft.blank().pinnedCertificate)
    }

}
