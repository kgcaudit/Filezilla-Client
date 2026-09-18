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
    val trustAllCertificates: Boolean,
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
        user = user.ifBlank { "anonymous" },
        passwordCipher = passwords.encrypt(password),
        security = security.name,
        transferMode = transferMode.name,
        trustAllCertificates = trustAllCertificates,
        initialPath = initialPath?.ifBlank { null },
        encoding = encoding?.ifBlank { null },
    )

    companion object {
        fun blank() = SiteDraft(
            id = UUID.randomUUID().toString(),
            name = "",
            host = "",
            port = 21,
            user = "anonymous",
            password = "anonymous@",
            security = FtpSecurity.EXPLICIT_TLS,
            transferMode = TransferMode.DEFAULT,
            trustAllCertificates = false,
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
                trustAllCertificates = site.trustAllCertificates,
                initialPath = site.initialPath,
                encoding = site.encoding,
                passwordUnreadable = plaintext == null,
            )
        }
    }
}
