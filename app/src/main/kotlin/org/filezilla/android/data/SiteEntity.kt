package org.filezilla.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.TransferMode
import org.filezilla.ftp.sftp.SftpSettings

/**
 * Which protocol a saved server speaks.
 *
 * FTP (plain or FTPS, told apart by [SiteEntity.security]) and SFTP are two
 * different protocols behind the same app surface, so this is stored rather
 * than inferred. Stored as its name so a value added later does not renumber
 * the existing rows.
 */
enum class SiteProtocol { FTP, SFTP }

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
    /**
     * Which protocol this server speaks: `"FTP"` (with [security] telling plain
     * from FTPS) or `"SFTP"`. Defaults to `"FTP"`, which is what every server
     * saved before SFTP existed was.
     */
    val protocol: String = SiteProtocol.FTP.name,
    /**
     * The SSH host key fingerprint accepted for this server, if any. The SFTP
     * parallel of [pinnedCertificate]: null means the server has not been
     * recognised yet, so the next connection stops and asks.
     */
    @ColumnInfo(name = "known_host_key") val knownHostKey: String? = null,
    /**
     * An SFTP private key in PEM form, held as ciphertext exactly like the
     * password -- see [passwordCipher]. Null means password authentication; a
     * key present means the login uses it, with the password taken as the key's
     * passphrase. A private key is a credential, so it never sits in a column
     * in the clear any more than the password does.
     */
    @ColumnInfo(name = "private_key_cipher") val privateKeyCipher: String? = null,
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

    /** Which protocol this server speaks; [SiteProtocol.FTP] for anything unset. */
    val protocolEnum: SiteProtocol
        get() = runCatching { enumValueOf<SiteProtocol>(protocol) }.getOrDefault(SiteProtocol.FTP)

    /**
     * The SFTP connection settings, with the password decrypted for the moment
     * it is needed -- the SFTP parallel of [toSettings].
     *
     * As there, a password that cannot be decrypted becomes an empty one rather
     * than an exception: the login then fails where the user can see and fix it.
     */
    fun toSftpSettings(passwords: PasswordCipher): SftpSettings = SftpSettings(
        host = host,
        port = port,
        user = user,
        password = passwords.decrypt(passwordCipher).orEmpty(),
        knownHostKey = knownHostKey,
        // A key that cannot be decrypted becomes null -- password auth -- rather
        // than an exception, the same forgiving path the password takes.
        privateKeyPem = privateKeyCipher?.let { passwords.decrypt(it) }?.takeIf { it.isNotBlank() },
    )
}
