package org.filezilla.android.ui

import org.filezilla.android.data.PasswordCipher
import org.filezilla.android.data.SiteEntity
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.TransferMode
import java.util.UUID

/**
 * A site as the editor works on it, with the password in plaintext.
 *
 * Separate from [SiteEntity] so that the decrypted password exists only while
 * a form is open, and cannot be written to the database by accident: there is
 * no plaintext field on the row to put it in.
 */
data class SiteDraft(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val security: FtpSecurity,
    val transferMode: TransferMode,
    /**
     * The certificate accepted for this server, carried through the editor
     * untouched. It is not something to type -- it is set by recognising a
     * certificate when one is presented, and cleared by the editor's own
     * button -- but editing and saving a site must not silently drop it.
     */
    val pinnedCertificate: String?,
    val initialPath: String?,
    /** Null means "negotiate", which is what most servers want. */
    val encoding: String? = null,
    /**
     * True when the saved password could not be decrypted, so the field is
     * empty because the secret is gone -- not because the user cleared it.
     * The editor says so instead of silently saving an empty password over
     * one that might still have been recoverable.
     */
    val passwordUnreadable: Boolean = false,
) {
    fun toEntity(passwords: PasswordCipher) = SiteEntity(
        id = id,
        name = name.ifBlank { host },
        host = host.trim(),
        port = port,
        user = user.ifBlank { ANONYMOUS_USER },
        // The anonymous convention only applies when the user left both
        // blank. A named account with no password is a choice some servers
        // allow, and substituting one would silently change what was typed.
        passwordCipher = passwords.encrypt(
            if (user.isBlank() && password.isBlank()) ANONYMOUS_PASSWORD else password,
        ),
        security = security.name,
        transferMode = transferMode.name,
        pinnedCertificate = pinnedCertificate,
        initialPath = initialPath?.ifBlank { null },
        encoding = encoding?.ifBlank { null },
    )

    companion object {
        const val ANONYMOUS_USER = "anonymous"
        const val ANONYMOUS_PASSWORD = "anonymous@"

        fun blank() = SiteDraft(
            id = UUID.randomUUID().toString(),
            name = "",
            host = "",
            port = 21,
            // Blank, not prefilled. These used to arrive as real values, so
            // anyone entering their own account had to backspace through
            // them first. They are hints in the form instead, and are
            // substituted at save time only if the fields are still empty.
            user = "",
            password = "",
            security = FtpSecurity.PLAIN,
            transferMode = TransferMode.DEFAULT,
            pinnedCertificate = null,
            initialPath = null,
            encoding = null,
        )

        fun of(site: SiteEntity, passwords: PasswordCipher): SiteDraft {
            val plaintext = passwords.decrypt(site.passwordCipher)
            return SiteDraft(
                id = site.id,
                name = site.name,
                host = site.host,
                port = site.port,
                user = site.user,
                password = plaintext.orEmpty(),
                security = site.securityEnum,
                transferMode = enumValueOf(site.transferMode),
                pinnedCertificate = site.pinnedCertificate,
                initialPath = site.initialPath,
                encoding = site.encoding,
                passwordUnreadable = plaintext == null,
            )
        }
    }
}
