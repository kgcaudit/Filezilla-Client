package org.filezilla.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.TransferMode
import org.filezilla.ftp.sftp.SftpSettings
import org.filezilla.ftp.smb.SmbSettings

/**
 * Which protocol a saved server speaks.
 *
 * FTP (plain or FTPS, told apart by [SiteEntity.security]) and SFTP are two
 * different protocols behind the same app surface, so this is stored rather
 * than inferred. Stored as its name so a value added later does not renumber
 * the existing rows.
 */
enum class SiteProtocol { FTP, SFTP, WEBDAV, SMB }

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
     * The SMB share this server connects to, e.g. `Public`. Its own column
     * rather than the first part of [initialPath]: a share is not a folder, and
     * making the user encode it into the path was a trap. Null on a site saved
     * before this column existed, where the share is still read from the path
     * for backward compatibility -- see [toSmbSettings].
     */
    @ColumnInfo(name = "smb_share") val smbShare: String? = null,
    /**
     * The NT domain or workgroup for SMB login, if any. Its own column rather
     * than `DOMAIN\user` crammed into [user]. Null means no domain, or a site
     * saved before this column existed (then the domain is read from [user]).
     */
    @ColumnInfo(name = "smb_domain") val smbDomain: String? = null,
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

    /**
     * The WebDAV connection settings, password decrypted for the moment it is
     * needed -- the WebDAV parallel of [toSettings].
     *
     * HTTP versus HTTPS is carried on [security] (PLAIN is http, anything else
     * https), so WebDAV needs no columns of its own. The base is the server
     * root; [initialPath] is the starting folder, exactly as it is for FTP and
     * SFTP -- a Nextcloud user puts their `/remote.php/dav/files/<user>` there.
     * A self-signed certificate the user has accepted is remembered as a pinned
     * certificate, exactly as FTPS does.
     */
    fun toWebDavSettings(passwords: PasswordCipher): org.filezilla.ftp.webdav.WebDavSettings =
        org.filezilla.ftp.webdav.WebDavSettings(
            host = host,
            port = port,
            https = securityEnum != FtpSecurity.PLAIN,
            user = user,
            password = passwords.decrypt(passwordCipher).orEmpty(),
            basePath = initialPath?.takeIf { it.isNotBlank() } ?: "/",
            pinnedFingerprint = pinnedCertificate,
        )

    /**
     * The SMB connection settings, password decrypted for the moment it is
     * needed -- the SMB parallel of [toSettings].
     *
     * SMB has no column of its own, so two fields are read for two parts each,
     * the way an SMB address is written:
     *  - [initialPath] is `\\server\SHARE\folder`'s tail: its first segment is
     *    the [SmbSettings.share] to connect, and the rest is the folder within it
     *    the root maps to. `/Public/docs` is share `Public`, folder `/docs`.
     *  - [user] may carry the NT domain the Windows way, `DOMAIN\user`; the part
     *    before the backslash is the domain, the rest the user. A plain `user`
     *    logs in with no domain, which is what a workgroup share wants.
     */
    fun toSmbSettings(passwords: PasswordCipher): SmbSettings {
        // Prefer the dedicated columns; fall back to the old convention (share
        // as the first path segment, domain as DOMAIN\user) for a site saved
        // before those columns existed, so no saved SMB site breaks.
        val dedicatedShare = smbShare?.takeIf { it.isNotBlank() }
        val share: String
        val folder: String
        if (dedicatedShare != null) {
            share = dedicatedShare.trim('/')
            folder = initialPath?.trim('/').orEmpty()
        } else {
            val path = initialPath?.trim('/').orEmpty()
            share = path.substringBefore('/')
            folder = path.substringAfter('/', "")
        }
        val legacyDomain = smbDomain == null && user.contains('\\')
        val domain = (smbDomain ?: user.takeIf { legacyDomain }?.substringBefore('\\'))
            ?.takeIf { it.isNotBlank() }
        val account = if (legacyDomain) user.substringAfter('\\') else user
        return SmbSettings(
            host = host,
            port = port,
            user = account,
            password = passwords.decrypt(passwordCipher).orEmpty(),
            domain = domain,
            share = share,
            basePath = if (folder.isEmpty()) "/" else "/$folder",
        )
    }
}
