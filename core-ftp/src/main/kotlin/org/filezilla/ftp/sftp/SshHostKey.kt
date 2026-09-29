package org.filezilla.ftp.sftp

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/**
 * A server's SSH host key, reduced to what somebody can actually be asked
 * about.
 *
 * The SSH parallel of [org.filezilla.ftp.net.ServerCertificate]. A host key is
 * signed by nobody, so no amount of checking makes it valid on its own; the
 * only thing that settles it is the person, once, by recognising the server --
 * and the only part small enough to recognise is its fingerprint.
 *
 * The fingerprint is printed the way OpenSSH and every SSH tool print it, so a
 * person can compare it against what `ssh-keygen -l` or their server's admin
 * page shows: `SHA256:` followed by the base64 of the key's SHA-256, without
 * padding. The legacy MD5 form is kept alongside it because some older tools
 * and NAS pages still show only that.
 */
data class SshHostKey(
    /** The key's algorithm, e.g. `ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`. */
    val algorithm: String,
    /** `SHA256:<base64>`, the modern fingerprint. What gets compared and pinned. */
    val fingerprintSha256: String,
    /** `MD5:aa:bb:...`, the legacy fingerprint, for servers that only show that. */
    val fingerprintMd5: String,
) {

    /**
     * What gets compared against a pin and stored as one.
     *
     * The SHA-256 form: MD5 fingerprints collide cheaply enough that pinning
     * one would defeat the point.
     */
    val fingerprint: String get() = fingerprintSha256

    companion object {

        /**
         * Reads a host key out of the raw public-key blob the SSH handshake
         * carried (the `K_S` the server sent).
         *
         * The blob is in SSH wire format: a length-prefixed string naming the
         * algorithm, then the key material. The algorithm name is read from
         * the front; the fingerprints are of the whole blob, which is exactly
         * what OpenSSH hashes.
         */
        fun of(keyBlob: ByteArray): SshHostKey = SshHostKey(
            algorithm = algorithmOf(keyBlob),
            fingerprintSha256 = "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(keyBlob)),
            fingerprintMd5 = "MD5:" + MessageDigest.getInstance("MD5").digest(keyBlob)
                .joinToString(":") { "%02x".format(it) },
        )

        /**
         * The algorithm name at the front of an SSH key blob, or a placeholder
         * when the blob is too short to carry one.
         *
         * A malformed blob is not worth throwing over: the fingerprints are
         * still computable and are what the person compares, so an unnamed
         * algorithm is shown as such rather than failing the connection.
         */
        private fun algorithmOf(keyBlob: ByteArray): String {
            if (keyBlob.size < 4) return "unknown"
            val length = ((keyBlob[0].toInt() and 0xFF) shl 24) or
                ((keyBlob[1].toInt() and 0xFF) shl 16) or
                ((keyBlob[2].toInt() and 0xFF) shl 8) or
                (keyBlob[3].toInt() and 0xFF)
            if (length <= 0 || length > keyBlob.size - 4) return "unknown"
            return String(keyBlob, 4, length, Charsets.US_ASCII)
        }
    }
}

/**
 * The server presented a host key that has not been recognised.
 *
 * The SSH parallel of [org.filezilla.ftp.net.CertificateNotTrusted], carried
 * out of the handshake rather than swallowed: only the person can settle it,
 * and they cannot be asked about a key nobody kept.
 */
class HostKeyNotTrusted(
    /** What the server actually presented. */
    val hostKey: SshHostKey,

    /**
     * What had been accepted for this server before, when something had.
     *
     * Null is the ordinary first meeting: nobody has said yes yet, and the app
     * asks. Non-null is the serious one -- a server that was trusted is now
     * presenting a different key, which is either a rotated key or somebody in
     * the middle, and the two look identical from here. Only the person can
     * tell them apart, and only if they are told it changed.
     */
    val previouslyTrusted: String?,

    cause: Throwable? = null,
) : IOException(
    if (previouslyTrusted == null) {
        "the server's host key has not been recognised"
    } else {
        "the server is presenting a different host key than the one accepted before"
    },
    cause,
) {
    /** True when a key that had been accepted has been replaced. */
    val changed: Boolean get() = previouslyTrusted != null
}
