package org.filezilla.ftp.protocol

/** How TLS is established, if at all. */
enum class FtpSecurity {
    /** Plain FTP, no encryption. */
    PLAIN,

    /** FTPS explicit: connect in the clear, then `AUTH TLS`. Port 21. */
    EXPLICIT_TLS,

    /** FTPS implicit: the connection is TLS from the first byte. Port 990. */
    IMPLICIT_TLS,
}

/**
 * Data connection mode, mirroring `CServer::GetPasvMode`.
 *
 * [DEFAULT] means passive, which is what works from behind NAT -- the client
 * opens the data connection outward. [ACTIVE] asks the server to connect back
 * to the client, which needs the client to be reachable: on a phone that
 * normally means the same LAN as the server, because a mobile carrier's NAT
 * will not forward the inbound connection.
 */
enum class TransferMode { PASSIVE, ACTIVE, DEFAULT }

/** Everything needed to open and drive one FTP connection. */
data class FtpSettings(
    val host: String,
    val port: Int = 21,
    val user: String = "anonymous",
    val password: String = "anonymous@",
    val security: FtpSecurity = FtpSecurity.EXPLICIT_TLS,

    /** Accept any server certificate. The app layer asks the user first. */
    val trustAllCertificates: Boolean = false,

    val transferMode: TransferMode = TransferMode.DEFAULT,
    val pasvFallbackMode: PasvFallbackMode = PasvFallbackMode.USE_SERVER_ADDRESS,

    /** Port of `OPTION_ALLOW_TRANSFERMODEFALLBACK`: PASV <-> PORT fallback. */
    val allowTransferModeFallback: Boolean = true,

    /** Port of `OPTION_PRESERVE_TIMESTAMPS`. */
    val preserveTimestamps: Boolean = true,

    /** Minutes to add to server-reported times, as in the Site Manager. */
    val timezoneOffsetMinutes: Int = 0,

    /**
     * Charset for the control connection, and so for filenames.
     *
     * Null negotiates it: UTF-8 when the server advertises `UTF8` in `FEAT`,
     * as most do. Naming one overrides that and suppresses `OPTS UTF8 ON`,
     * which is what a server storing filenames in a legacy encoding needs --
     * EUC-KR on many Korean NAS boxes, and there is no way to detect it, so
     * the user has to say.
     */
    val encoding: String? = null,

    val connectTimeoutMillis: Int = 20_000,

    /**
     * How long to wait on a silent control connection before giving up.
     *
     * 20 seconds matches FileZilla's `Timeout` default
     * (`engine_options.cpp:19`), which also refuses values below 10. It
     * matters most when a transfer dies mid-flight: the server often sends no
     * closing reply at all -- the network that killed the data connection took
     * the control channel with it -- so this timeout is what turns a dead
     * connection into a retry rather than a hang.
     */
    val readTimeoutMillis: Int = 20_000,

    /** Port of `OPTION_RECONNECTCOUNT`. */
    val maxRetries: Int = 5,
) {
    val serverKey: ServerCapabilities.ServerKey
        get() = ServerCapabilities.ServerKey(host, port, user)
}
