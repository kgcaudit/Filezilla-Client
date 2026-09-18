package org.filezilla.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.TransferMode

/**
 * A saved server, the app's equivalent of a FileZilla Site Manager entry.
 *
 * The password is stored in plain text in the app's private database. That is
 * the same exposure as FileZilla's own `sitemanager.xml`, and it is private to
 * the app on a non-rooted device -- but it is not encrypted, so it does not
 * survive a device backup or a rooted phone safely. Moving it behind the
 * Android keystore is tracked as its own piece of work rather than pretended
 * at here.
 */
@Entity(tableName = "sites")
data class SiteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val security: String,
    val transferMode: String,
    val trustAllCertificates: Boolean,
    val initialPath: String?,
) {
    fun toSettings(): FtpSettings = FtpSettings(
        host = host,
        port = port,
        user = user,
        password = password,
        security = enumValueOf<FtpSecurity>(security),
        trustAllCertificates = trustAllCertificates,
        transferMode = enumValueOf<TransferMode>(transferMode),
    )

    val securityEnum: FtpSecurity get() = enumValueOf<FtpSecurity>(security)
}
