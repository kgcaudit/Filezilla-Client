package org.filezilla.ftp.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import org.filezilla.ftp.smb.crypto.AndroidSmbSecurityProvider
import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.listing.EntryTime
import org.filezilla.ftp.listing.TimeAccuracy
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * One connected SMB share, with the browsing and transfer operations the UI
 * needs hung off it -- the SMB parallel of [org.filezilla.ftp.sftp.SftpEngine].
 *
 * Built on smbj, the pure-Java SMB2 client, but pinned to a shape that runs on
 * Android as readily as on the JVM:
 *
 *  - **A JCE-based security provider, never BouncyCastle's.** smbj's crypto
 *    defaults to BouncyCastle, which on Android (<=API 27) is shadowed by the
 *    stripped `org.bouncycastle` on the platform bootclasspath, so a call into a
 *    newer BouncyCastle API crashes. [AndroidSmbSecurityProvider] routes crypto
 *    through the platform's own JCE and fills the two gaps Android has
 *    (AES-CMAC, AES-CCM) with its own code on the platform AES block cipher, so
 *    no BouncyCastle crypto runs. (BouncyCastle still ships, because smbj's NTLM
 *    login uses its `org.bouncycastle.util.Arrays` directly -- a stable utility
 *    that is safe shadowed -- but none of its crypto is reached.)
 *  - **SMB 2.0.2 through 3.1.1.** Signing is HMAC-SHA256 (2.x) or AES-CMAC (3.x);
 *    encryption, when a share requires it, is AES-CCM (3.0/3.0.2) or AES-GCM
 *    (3.1.1). GCM, SHA-512 pre-auth and the SP800-108 key derivation are the
 *    platform's; CMAC and CCM are [AndroidSmbSecurityProvider]'s own. The highest
 *    dialect the server offers is taken.
 *
 * Server-side answers (no such file, access denied) arrive from smbj as
 * [SMBApiException], a `RuntimeException` -- so, exactly as
 * [org.filezilla.ftp.sftp.SftpStatusException] is for SFTP, they are not the
 * [java.io.IOException] a transfer retries on. A dropped connection is smbj's
 * [com.hierynomus.protocol.transport.TransportException], which is an
 * `IOException`, so that is retried as the lost socket it is.
 *
 * Paths are the app's own, `/`-rooted and relative to the share; this turns them
 * into the backslash paths smbj wants. Not thread-safe: one of these belongs to
 * one caller at a time.
 */
class SmbClient(private val settings: SmbSettings) : Closeable {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private val disk: DiskShare get() = share ?: throw java.io.IOException("not connected")

    /**
     * Runs an smbj call, turning its server-side [SMBApiException] into an
     * [SmbStatusException] the app can read -- the SMB parallel of
     * [org.filezilla.ftp.sftp.SftpEngine]'s `operation`. A dropped connection is
     * smbj's own `TransportException`, an `IOException`, and is left to pass
     * through so a transfer retries it.
     */
    private inline fun <T> op(block: () -> T): T = try {
        block()
    } catch (e: SMBApiException) {
        throw translate(e)
    }

    private fun translate(e: SMBApiException): SmbStatusException {
        val kind = when (e.status) {
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_ACCOUNT_DISABLED,
            NtStatus.STATUS_PASSWORD_EXPIRED,
            NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
            -> SmbStatusException.Kind.LOGIN_FAILED
            NtStatus.STATUS_BAD_NETWORK_NAME -> SmbStatusException.Kind.SHARE_NOT_FOUND
            NtStatus.STATUS_ACCESS_DENIED -> SmbStatusException.Kind.ACCESS_DENIED
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            -> SmbStatusException.Kind.NOT_FOUND
            else -> SmbStatusException.Kind.OTHER
        }
        return SmbStatusException(kind, e.message ?: "SMB error", e)
    }

    // -------------------------------------------------------------- connect

    fun connect() {
        val config = SmbConfig.builder()
            .withSecurityProvider(AndroidSmbSecurityProvider())
            .withDialects(
                SMB2Dialect.SMB_3_1_1,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_2_0_2,
            )
            // NTLM only. smbj's default list also carries a SPNEGO/Kerberos
            // authenticator that reaches for `org.ietf.jgss`, which Android does
            // not ship; a user-and-password login never needs it, so it is left
            // out rather than risked. User/password auth to Samba and Windows is
            // NTLM, which this keeps.
            .withAuthenticators(NtlmAuthenticator.Factory())
            // Advertise encryption so an encrypted share is reachable -- Samba
            // refuses the tree connect otherwise -- and encrypt SMB 3 traffic by
            // default. It is not a demand: a server that cannot or will not
            // encrypt (SMB 2.x, or SMB 3 with encryption off) is still used
            // unencrypted. AES-GCM (3.1.1) and AES-CCM (3.0/3.0.2) come from
            // AndroidSmbSecurityProvider.
            .withEncryptData(true)
            .withTimeout(settings.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .withSoTimeout(settings.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val newClient = SMBClient(config)
        val newConnection = newClient.connect(settings.host, settings.port)
        val auth = AuthenticationContext(
            settings.user,
            settings.password.toCharArray(),
            settings.domain?.takeIf { it.isNotBlank() },
        )
        val newSession = op { newConnection.authenticate(auth) }
        val newShare = op { newSession.connectShare(settings.share) } as? DiskShare
            ?: throw java.io.IOException("'${settings.share}' is not a disk share")
        client = newClient
        connection = newConnection
        session = newSession
        share = newShare
    }

    // ------------------------------------------------------------- browsing

    /** The entries of [dirPath] (an app path relative to the share). */
    fun list(dirPath: String): List<DirectoryEntry> = op {
        disk.list(smbPath(dirPath))
            .filterNot { it.fileName == "." || it.fileName == ".." }
            .map { it.toDirectoryEntry() }
    }

    fun makeDirectory(path: String) = op { disk.mkdir(smbPath(path)) }

    fun removeDirectory(path: String) = op { disk.rmdir(smbPath(path), false) }

    fun deleteFile(path: String) = op { disk.rm(smbPath(path)) }

    /** Renames [from] to [to], both app paths; the folder may change. */
    fun rename(from: String, to: String) = op {
        disk.open(
            smbPath(from),
            setOf(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { it.rename(smbPath(to), false) }
    }

    /**
     * What the server says about [path] right now, for resume safety. A file
     * that is not there is a usable answer -- an empty fingerprint -- not a
     * failure, exactly as [org.filezilla.ftp.sftp.SftpEngine.fingerprint] treats
     * a missing file.
     */
    fun fingerprint(path: String): RemoteFingerprint = try {
        val info = disk.getFileInformation(smbPath(path))
        val isDir = info.standardInformation.isDirectory
        RemoteFingerprint(
            size = info.standardInformation.endOfFile.takeIf { !isDir },
            modifiedMillis = info.basicInformation.lastWriteTime.toEpochMillis(),
        )
    } catch (e: SMBApiException) {
        RemoteFingerprint(size = null, modifiedMillis = null)
    }

    // ----------------------------------------------------------- transferring

    /**
     * Streams [path] to [out] from [offset], so a half-finished download
     * resumes. Returns the total size the server reported.
     */
    fun download(path: String, offset: Long, out: OutputStream, onProgress: (Long) -> Unit): Long = op {
        disk.openFile(
            smbPath(path),
            setOf(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { file ->
            val total = file.fileInformation.standardInformation.endOfFile
            var position = offset
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = file.read(buffer, position, 0, buffer.size)
                if (read < 0) break
                out.write(buffer, 0, read)
                position += read
                onProgress(position)
            }
            total
        }
    }

    /**
     * Sends [input] to [path], writing from [offset] so a partly-sent file
     * resumes. At offset 0 the file is created or truncated; at a non-zero
     * offset the existing file is opened and written onto.
     */
    fun upload(path: String, input: InputStream, offset: Long, onProgress: (Long) -> Unit) = op {
        val disposition =
            if (offset > 0) SMB2CreateDisposition.FILE_OPEN else SMB2CreateDisposition.FILE_OVERWRITE_IF
        disk.openFile(
            smbPath(path),
            setOf(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            disposition,
            null,
        ).use { file ->
            var position = offset
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                file.write(buffer, position, 0, read)
                position += read
                onProgress(position - offset)
            }
        }
    }

    /**
     * Makes the folders [path] sits in, ignoring the ones already there -- the
     * SMB parallel of [org.filezilla.ftp.sftp.SftpEngine]'s `ensureParentsOf`.
     */
    fun ensureParentsOf(path: String) {
        val parent = smbPath(path).substringBeforeLast('\\', "")
        if (parent.isEmpty()) return
        val parts = parent.split('\\').filter { it.isNotEmpty() }
        var built = ""
        for (part in parts) {
            built = if (built.isEmpty()) part else "$built\\$part"
            if (!disk.folderExists(built)) {
                runCatching { disk.mkdir(built) }
                    .onFailure { if (it !is SMBApiException) throw it }
            }
        }
    }

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        connection = null
        client = null
    }

    // -------------------------------------------------------------- plumbing

    private fun FileIdBothDirectoryInformation.toDirectoryEntry(): DirectoryEntry {
        val isDir = fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
        return DirectoryEntry(
            name = fileName,
            // A folder's own size is not what a person means by its size, so it
            // is left unknown, matching how the other engines list a directory.
            size = if (isDir) -1L else endOfFile,
            isDirectory = isDir,
            time = EntryTime(lastWriteTime.toEpochMillis(), TimeAccuracy.SECONDS),
        )
    }

    /**
     * An app path (`/a/b`, relative to the session root) as the backslash path
     * smbj wants, under [SmbSettings.basePath] -- so a site rooted at a folder
     * within the share browses relative to it, as WebDAV's base does.
     */
    private fun smbPath(appPath: String): String {
        val base = settings.basePath.trim('/')
        val rel = appPath.trim('/')
        val joined = when {
            base.isEmpty() -> rel
            rel.isEmpty() -> base
            else -> "$base/$rel"
        }
        return joined.replace('/', '\\')
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
