package org.filezilla.ftp.net

import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Creates the TLS sockets for one server connection.
 *
 * A single [SSLContext] is held for the whole connection because the client
 * session cache lives on the context: that shared cache is what lets the data
 * channel resume the control channel's session.
 */
class TlsFactory(
    /**
     * When set, certificates are not verified. Only for connecting to a server
     * whose self-signed certificate the user has explicitly accepted; the app
     * layer is responsible for asking.
     */
    private val trustAllCertificates: Boolean = false,
    private val minimumProtocol: String = "TLSv1.2",
) {
    private val context: SSLContext = SSLContext.getInstance("TLS").also { ctx ->
        val trustManagers: Array<TrustManager>? =
            if (trustAllCertificates) arrayOf(TrustEverything) else null
        ctx.init(null, trustManagers, SecureRandom())
    }

    private val factory: SSLSocketFactory get() = context.socketFactory

    /** Upgrades the plain control socket in place, after `AUTH TLS`. */
    fun upgradeControl(plain: Socket, host: String, port: Int): SSLSocket {
        val socket = factory.createSocket(plain, host, port, true) as SSLSocket
        socket.useClientMode = true
        applyProtocols(socket)
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
        return socket
    }

    private fun applyProtocols(socket: SSLSocket) {
        val order = listOf("TLSv1.2", "TLSv1.3")
        val minIndex = order.indexOf(minimumProtocol).takeIf { it >= 0 } ?: 0
        val wanted = order.drop(minIndex).toSet()
        val enabled = socket.supportedProtocols.filter { it in wanted }
        if (enabled.isNotEmpty()) socket.enabledProtocols = enabled.toTypedArray()
    }

    private object TrustEverything : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
