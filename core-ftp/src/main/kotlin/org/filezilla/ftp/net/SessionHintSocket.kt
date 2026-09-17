package org.filezilla.ftp.net

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel

/**
 * Wraps a connected data-channel socket but reports the **control** channel's
 * endpoint, so the TLS client session cache is looked up under the control
 * connection's key and offers that session for resumption.
 *
 * Servers with `require_ssl_reuse` -- vsftpd's default, ProFTPD's `mod_tls`,
 * FileZilla Server -- reject a data connection that does not resume the
 * control session. FileZilla enforces the same rule from its side
 * (`transfersocket.cpp:220-256`).
 *
 * JSSE keys its client session cache on host and port, and takes the port from
 * the *wrapped* socket (`BaseSSLSocketImpl.getPort()` delegates), so passing
 * the control host/port to `createSocket` alone still misses. Conscrypt on
 * Android honours the `createSocket` arguments directly, making this wrapper
 * redundant there but harmless, so one code path works on both.
 *
 * Measured in `spikes/ftps-session-reuse/`: without the wrapper the server
 * reports `SSL_session_reused=False` and answers `522`; with it, `True` and the
 * transfer completes, on both TLS 1.2 and TLS 1.3.
 */
internal class SessionHintSocket(
    private val delegate: Socket,
    private val hintAddress: InetAddress,
    private val hintPort: Int,
) : Socket() {

    // The hint itself.
    override fun getInetAddress(): InetAddress = hintAddress
    override fun getPort(): Int = hintPort
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(hintAddress, hintPort)

    // Everything else is the real data socket.
    override fun getInputStream(): InputStream = delegate.getInputStream()
    override fun getOutputStream(): OutputStream = delegate.getOutputStream()
    override fun close() = delegate.close()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getLocalPort(): Int = delegate.localPort
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun getChannel(): SocketChannel? = delegate.channel
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
    override fun getSoLinger(): Int = delegate.soLinger
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun setTrafficClass(tc: Int) { delegate.trafficClass = tc }
    override fun getTrafficClass(): Int = delegate.trafficClass
    override fun setOOBInline(on: Boolean) { delegate.oobInline = on }
    override fun getOOBInline(): Boolean = delegate.oobInline
    override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)
}
