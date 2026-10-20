package org.filezilla.ftp.smb

/**
 * A server-side SMB error, in terms the app can read without seeing smbj.
 *
 * smbj answers a refused request with an `SMBApiException` carrying a raw
 * `STATUS_...` code -- and that type lives in smbj, which the app module does
 * not compile against (it is an `implementation` dependency of this module, kept
 * off the app's classpath). So [SmbClient] translates it to this, the SMB
 * parallel of [org.filezilla.ftp.sftp.SftpStatusException]: a small [kind] the
 * app maps to a sentence and a next step, and the server's own message kept
 * underneath as evidence.
 *
 * A `RuntimeException` and pointedly not an `IOException`, so a transfer's retry
 * loop -- which retries a dropped socket -- leaves it alone: the server has
 * answered, and asking again changes nothing.
 */
class SmbStatusException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** What went wrong, coarse enough for one clear sentence each. */
    enum class Kind {
        /** The username, password or domain was not accepted. */
        LOGIN_FAILED,

        /** No share by that name on the server. */
        SHARE_NOT_FOUND,

        /** Logged in, but not allowed to do this. */
        ACCESS_DENIED,

        /** No file or folder at that path. */
        NOT_FOUND,

        /** Anything else; the raw message carries the detail. */
        OTHER,
    }
}
