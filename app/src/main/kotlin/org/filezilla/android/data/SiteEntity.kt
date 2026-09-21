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
    /**
     * The certificate fingerprint this server has been recognised on.
     *
     * Replaces a switch that turned certificate checking off for the server
     * entirely. That switch was not really optional -- a home server's
     * certificate is signed by nobody, so it was the price of connecting at
     * all -- and once paid, the connection stayed encrypted but stopped
     * being addressed to anyone in particular. This holds one certificate
     * instead of accepting all of them.
     */
    @ColumnInfo(name = "pinned_certificate") val pinnedCertificate: String? = null,
    val initialPath: String?,
    /** Null negotiates UTF-8; a name pins it. See [FtpSettings.encoding]. */
    val encoding: String? = null,
    /**
     * Where the site sits in the user's own order, smallest first.
     *
     * The list was sorted by name, which is an order nobody chose: the server
     * used every day sat wherever its name happened to fall. This is the one
     * the user arranged, so it is stored rather than derived.
     */
    val position: Int = 0,
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
        pinnedCertificate = pinnedCertificate,
        transferMode = enumValueOf<TransferMode>(transferMode),
        encoding = encoding,
    )

    val securityEnum: FtpSecurity get() = enumValueOf<FtpSecurity>(security)
}
