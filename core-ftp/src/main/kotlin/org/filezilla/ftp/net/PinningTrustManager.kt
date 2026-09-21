package org.filezilla.ftp.net

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts a server the way SSH trusts a host: by recognising it.
 *
 * Two rules, and which one applies depends only on whether this server has
 * been accepted before.
 *
 *  - **Nothing accepted yet.** The certificate has to stand on its own --
 *    signed by a certificate authority the phone ships, and in date. That
 *    is the ordinary web rule and it is the right one for a server that
 *    can meet it. When it cannot, nothing is trusted and
 *    [CertificateNotTrusted] carries what was seen so the person can be
 *    asked about it.
 *  - **Something accepted.** Only that exact certificate, matched by
 *    fingerprint. Not the authority that signed it, not another
 *    certificate for the same name -- that one. Anything else is reported
 *    as a change, loudly, because a server swapping its certificate and
 *    somebody standing in the middle of the connection look the same from
 *    here.
 *
 * What this replaces accepted everything for a server once the user turned
 * a switch on. It was the only way to reach a home server, whose
 * certificate is signed by nobody -- so the switch was not optional, it
 * was the price of using the app at all. Having paid it, the connection
 * stayed encrypted but stopped being addressed to anyone in particular:
 * on a network somebody else runs, the password goes to whoever answers.
 */
class PinningTrustManager(
    /** The fingerprint already accepted for this server, if any. */
    private val pinned: String?,
) : X509TrustManager {

    /**
     * What the server presented, kept whether or not it was accepted.
     *
     * A handshake failure surfaces as an [javax.net.ssl.SSLHandshakeException]
     * from deep inside the socket, and the exception a trust manager throws
     * does not always survive the trip. So the certificate is recorded here
     * as well, and the connection reads it back to say what it was that
     * could not be trusted.
     */
    @Volatile
    var lastSeen: ServerCertificate? = null
        private set

    /** Set when this manager is the reason the handshake failed. */
    @Volatile
    var refusal: CertificateNotTrusted? = null
        private set

    private val platform: X509TrustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("the server sent no certificate")
        val seen = ServerCertificate.of(leaf)
        lastSeen = seen

        if (pinned != null) {
            if (seen.fingerprint.equals(pinned, ignoreCase = true)) return
            refuse(seen, previouslyTrusted = pinned, cause = null)
        }

        // Nothing accepted for this server yet, so it has to be verifiable on
        // its own. A certificate that manages that needs no pin and never
        // gets one, which is what keeps a renewed public certificate from
        // asking the user every ninety days.
        try {
            platform.checkServerTrusted(chain, authType)
        } catch (failed: CertificateException) {
            refuse(seen, previouslyTrusted = null, cause = failed)
        }
    }

    private fun refuse(
        seen: ServerCertificate,
        previouslyTrusted: String?,
        cause: Throwable?,
    ): Nothing {
        val refused = CertificateNotTrusted(seen, previouslyTrusted, cause)
        refusal = refused
        throw CertificateException(refused.message, refused)
    }

    /**
     * What to raise for a handshake that failed, if anything.
     *
     * [refusal] covers what this class turned down itself. It does not
     * cover the check that the certificate is *for this host*, which the
     * socket performs after the trust manager has already said yes -- so a
     * certificate signed by a real authority, for a name this server is
     * not reached by, threw a bare handshake failure and left nothing to
     * show. With the "accept any certificate" switch gone, that was a
     * server nobody could connect to and no dialog to say why.
     *
     * A certificate was seen and something about the certificate was
     * wrong: that is the same question as any other unrecognised one, and
     * accepting it pins it -- which also turns the host check off, because
     * the pin is a stricter identity than the name.
     *
     * Narrow on purpose. A handshake that fails over protocol versions or
     * ciphers is not a question about identity, and putting a fingerprint
     * in front of somebody for one would teach them to accept fingerprints.
     */
    fun refusalFor(failure: Throwable): CertificateNotTrusted? {
        refusal?.let { return it }
        val seen = lastSeen ?: return null
        val aboutTheCertificate = generateSequence(failure) { it.cause }
            .any { it is CertificateException || it is SSLPeerUnverifiedException }
        return if (aboutTheCertificate) CertificateNotTrusted(seen, null, failure) else null
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        platform.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}
