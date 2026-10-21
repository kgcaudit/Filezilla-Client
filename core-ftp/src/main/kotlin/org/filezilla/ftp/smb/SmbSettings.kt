package org.filezilla.ftp.smb

/**
 * What an SMB connection needs: where the server is, who to log in as, and which
 * share and folder on it the app browses from.
 *
 * SMB is not FTP: a server hosts named *shares* (`\\server\share`), and every
 * path is relative to one. So unlike FTP a connection targets one [share], and
 * [basePath] is the folder within it the user's root maps to -- the SMB parallel
 * of an FTP site's initial path. A [domain] is the Windows/NT domain or
 * workgroup; null or blank logs in without one, which is what a workgroup share
 * and most NAS boxes want.
 *
 * There is no TLS and so no certificate here, unlike FTPS or WebDAV: SMB carries
 * its own signing over the session, negotiated at connect time.
 */
data class SmbSettings(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val user: String,
    val password: String,
    val domain: String? = null,
    /** The share to connect, e.g. "Public" -- the first `\\server\HERE` part. */
    val share: String,
    /** The folder within the share the root maps to, e.g. "/" or "/docs". */
    val basePath: String = "/",
    /** Socket read timeout, applied to smbj's transaction and SO timeouts. */
    val readTimeoutMillis: Int = 60_000,
) {
    companion object {
        const val DEFAULT_PORT = 445
    }
}
