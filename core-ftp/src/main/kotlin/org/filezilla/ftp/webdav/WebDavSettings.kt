package org.filezilla.ftp.webdav

/**
 * What a WebDAV connection needs: where the server is, who to log in as, and
 * which folder on it is the root the app browses from.
 *
 * WebDAV is HTTP, so "the server" is a scheme, host and port, and a path. The
 * [basePath] is the collection the user's root maps to -- a Nextcloud share
 * lives under `/remote.php/dav/files/<user>/`, a plain Apache `mod_dav` under
 * `/`, and the user should not have to type that on every folder. Paths handed
 * to the client are relative to it.
 */
data class WebDavSettings(
    val host: String,
    val port: Int,
    val https: Boolean,
    val user: String,
    val password: String,
    /** The collection the root maps to, e.g. "/" or "/remote.php/dav/files/me". */
    val basePath: String = "/",
    /**
     * Accept a TLS certificate that does not chain to a trusted root -- a home
     * server's self-signed one. Off by default; the app turns it on only once
     * the user has seen and accepted the certificate, as it does for FTPS.
     */
    val allowUntrustedCertificate: Boolean = false,
) {
    /** The scheme-host-port the paths hang off, with no trailing slash. */
    val origin: String get() = "${if (https) "https" else "http"}://$host:$port"

    companion object {
        fun defaultPort(https: Boolean): Int = if (https) 443 else 80
    }
}
