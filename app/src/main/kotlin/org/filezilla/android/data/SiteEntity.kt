package org.filezilla.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.TransferMode

/**
 * A saved server, the app's equivalent of a FileZilla Site Manager entry.
 *
 * The password is held as ciphertext under a key the Android keystore owns and
 * never hands out, so the database file on its own is useless -- see
 * [PasswordCipher] for what that does and does not protect against. The
 * plaintext exists only while a transfer or an edit needs it, and never in a
 * column.
 */
@Entity(tableName = "sites")
data class SiteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    @ColumnInfo(name = "password_cipher") val passwordCipher: String,
    val security: String,
    val transferMode: String,
    val trustAllCertificates: Boolean,
    val initialPath: String?,
) {
    /**
     * The connection settings, with the password decrypted for the moment it
     * is needed.
     *
     * A password that cannot be decrypted becomes an empty one rather than an
     * exception: the keystore key is gone, the login will fail, and a failed
     * login the user can see and fix beats a crash on a background thread.
     */
    fun toSettings(passwords: PasswordCipher): FtpSettings = FtpSettings(
        host = host,
        port = port,
        user = user,
        password = passwords.decrypt(passwordCipher).orEmpty(),
        security = enumValueOf<FtpSecurity>(security),
        trustAllCertificates = trustAllCertificates,
        transferMode = enumValueOf<TransferMode>(transferMode),
    )

    val securityEnum: FtpSecurity get() = enumValueOf<FtpSecurity>(security)
}
