package org.filezilla.ftp.net

import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/**
 * Creates the TLS sockets for one server connection.
 *
 * A single [SSLContext] is held for the whole connection because the client
 * session cache lives on the context: that shared cache is what lets the data
 * channel resume the control channel's session.
 */
class TlsFactory(
    /**
     * The certificate fingerprint already accepted for this server, if any.
     *
     * Null means nothing has been accepted, so the certificate has to be
     * valid on its own terms. See [PinningTrustManager] for both rules.
     */
    private val pinnedCertificate: String? = null,
    private val minimumProtocol: String = "TLSv1.2",
) {
    /**
     * Kept so the connection can say what certificate it was that could not
     * be trusted, once the handshake has failed.
     */
    val trust = PinningTrustManager(pinnedCertificate)

    private val context: SSLContext = SSLContext.getInstance("TLS").also { ctx ->
        ctx.init(null, arrayOf<TrustManager>(trust), SecureRandom())
    }

    private val factory: SSLSocketFactory get() = context.socketFactory

    /** Upgrades the plain control socket in place, after `AUTH TLS`. */
    fun upgradeControl(plain: Socket, host: String, port: Int): SSLSocket {
        val socket = factory.createSocket(plain, host, port, true) as SSLSocket
        socket.useClientMode = true
        applyProtocols(socket)
        applyHostnameCheck(socket)
        socket.startHandshake()
        return socket
    }

    /**
     * Wraps a connected data socket so it resumes the control connection's TLS
     * session.
     *
     * [controlAddress] and [controlPort] identify the control connection, and
     * are applied both as the `createSocket` arguments and, via
     * [SessionHintSocket], as the wrapped socket's reported endpoint. Both are
     * needed -- see the class docs on [SessionHintSocket].
     *
     * The handshake is deliberately *not* started here. It has to happen after
     * the transfer command (`RETR`/`STOR`) has been sent, because some servers
     * only begin the data-channel handshake at that point.
     */
    fun wrapDataChannel(
        plain: Socket,
        controlHost: String,
        controlAddress: InetAddress,
        controlPort: Int,
        reuseControlSession: Boolean = true,
    ): SSLSocket {
        val toWrap = if (reuseControlSession) {
            SessionHintSocket(plain, controlAddress, controlPort)
        } else {
            plain
        }
        val host = if (reuseControlSession) controlHost else plain.inetAddress.hostAddress
        val port = if (reuseControlSession) controlPort else plain.port
        val socket = factory.createSocket(toWrap, host, port, true) as SSLSocket
        socket.useClientMode = true
        applyProtocols(socket)
        applyHostnameCheck(socket)
        return socket
    }

    /**
     * Turns on the check that the certificate is for the host being talked
     * to, which a bare [SSLSocket] does not do.
     *
     * Without it a certificate signed by a real authority for some entirely
     * different domain passes: the chain is valid, and nothing was looking
     * at the name. That is a hole the pinning below does not cover, because
     * it only applies to servers that have no pin.
     *
     * Deliberately off for a pinned server. A home server's certificate
     * names whatever it was generated with, rarely the address it is
     * reached at, and it has already been identified by something stricter
     * than its name: the person recognised that exact certificate. Failing
     * it on the name after that would rule out the servers this is for.
     */
    internal fun applyHostnameCheck(socket: SSLSocket) {
        if (pinnedCertificate != null) return
        socket.sslParameters = socket.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
        }
    }

    private fun applyProtocols(socket: SSLSocket) {
        val order = listOf("TLSv1.2", "TLSv1.3")
        val minIndex = order.indexOf(minimumProtocol).takeIf { it >= 0 } ?: 0
        val wanted = order.drop(minIndex).toSet()
        val enabled = socket.supportedProtocols.filter { it in wanted }
        if (enabled.isNotEmpty()) socket.enabledProtocols = enabled.toTypedArray()
    }
}
