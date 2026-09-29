package org.filezilla.ftp.sftp

/**
 * Everything needed to open and drive one SFTP connection.
 *
 * Deliberately a type of its own rather than a mode bolted onto
 * [org.filezilla.ftp.protocol.FtpSettings]: SFTP is SSH, not FTP with TLS, and
 * nothing it needs -- passive/active data channels, `FEAT`, a TLS security
 * level -- has any meaning here. What it does need that FTP does not is a host
 * key to recognise the server by, which is [knownHostKey].
 */
data class SftpSettings(
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String = "",

    /**
     * The host key fingerprint already accepted for this server, if any.
     *
     * The direct parallel of [org.filezilla.ftp.protocol.FtpSettings.pinnedCertificate].
     * Null means the server has not been recognised yet, so the first
     * connection raises [HostKeyNotTrusted] carrying what it presented, for the
     * app to put in front of the person. Once they recognise it, its
     * fingerprint comes back here and only it is accepted -- a different key on
     * a later connection is reported as a change, loudly, because a server
     * rotating its key and somebody standing in the middle look the same from
     * here.
     */
    val knownHostKey: String? = null,

    /**
     * A PEM private key for public-key authentication, or null for password
     * auth. When set, [password] is treated as the key's passphrase (empty
     * when the key is not encrypted).
     */
    val privateKeyPem: String? = null,

    val connectTimeoutMillis: Int = 20_000,

    /**
     * How long to wait on a silent connection before giving up. Matches
     * [org.filezilla.ftp.protocol.FtpSettings.readTimeoutMillis]: it is what
     * turns a connection the network killed into a retry rather than a hang.
     */
    val readTimeoutMillis: Int = 20_000,

    /** Whether to carry the source file's modification time to the server. */
    val preserveTimestamps: Boolean = true,

    /** How many times a failed transfer is reconnected and resumed. */
    val maxRetries: Int = 5,
)
