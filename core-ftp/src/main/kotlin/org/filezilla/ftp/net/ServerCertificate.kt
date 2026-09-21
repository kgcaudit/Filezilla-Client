package org.filezilla.ftp.net

import java.io.IOException
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * A server's certificate, reduced to what somebody can actually be asked
 * about.
 *
 * The question a self-signed certificate poses is not one a phone can
 * answer: a home server's certificate is signed by nobody, so no amount of
 * checking will make it valid. The only thing that can settle it is the
 * person, once, by recognising the server -- and the only part of a
 * certificate small enough to recognise is its fingerprint.
 *
 * So this carries the fingerprint, and enough around it to tell one server
 * from another when the fingerprint is not what was expected.
 */
data class ServerCertificate(
    /** SHA-256 of the certificate, uppercase hex in pairs. What gets compared. */
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
) {

    /** True when the certificate vouches for itself, as a home server's does. */
    val selfSigned: Boolean get() = subject == issuer

    /** The name the certificate is actually for, without the rest of the subject. */
    val commonName: String get() = nameIn(subject) ?: subject

    /** Who signed it, short. */
    val issuerName: String get() = nameIn(issuer) ?: issuer

    fun isCurrentAt(millis: Long): Boolean = millis in notBeforeMillis..notAfterMillis

    companion object {

        fun of(certificate: X509Certificate): ServerCertificate = ServerCertificate(
            fingerprint = fingerprintOf(certificate.encoded),
            subject = certificate.subjectX500Principal.name,
            issuer = certificate.issuerX500Principal.name,
            notBeforeMillis = certificate.notBefore.time,
            notAfterMillis = certificate.notAfter.time,
        )

        /**
         * SHA-256, in the shape a person can compare against what their
         * server tells them.
         *
         * Pairs separated by colons, uppercase, because that is how every
         * tool that prints one does it -- `openssl x509 -fingerprint`, a
         * NAS admin page, FileZilla's own dialog. A fingerprint that has to
         * be reformatted before it can be compared is one nobody compares.
         */
        fun fingerprintOf(encoded: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(encoded)
                .joinToString(":") { "%02X".format(it) }

        /** The CN out of an X.500 name, which is the part worth showing. */
        private fun nameIn(principal: String): String? =
            Regex("""(?:^|,)\s*CN=((?:[^,\\]|\\.)*)""").find(principal)
                ?.groupValues?.get(1)
                ?.replace(Regex("""\\(.)"""), "$1")
                ?.takeIf { it.isNotBlank() }
    }
}

/**
 * The server presented a certificate that nothing here can vouch for.
 *
 * Carried out of the handshake rather than swallowed, because the only
 * thing that can settle it is the person, and they cannot be asked about a
 * certificate nobody kept.
 */
class CertificateNotTrusted(
    /** What the server actually presented. */
    val certificate: ServerCertificate,

    /**
     * What had been accepted for this server before, when something had.
     *
     * Null is the ordinary first meeting: nobody has said yes yet, and the
     * app asks. Non-null is the serious one -- a server that was trusted is
     * now presenting something else, which is either a renewed certificate
     * or somebody standing in the middle, and the two look identical from
     * here. Only the person can tell them apart, and only if they are told
     * it changed.
     */
    val previouslyTrusted: String?,

    cause: Throwable? = null,
) : IOException(
    if (previouslyTrusted == null) {
        "the server's certificate is not signed by anyone this phone trusts"
    } else {
        "the server is presenting a different certificate than the one accepted before"
    },
    cause,
) {
    /** True when a certificate that had been accepted has been replaced. */
    val changed: Boolean get() = previouslyTrusted != null
}
