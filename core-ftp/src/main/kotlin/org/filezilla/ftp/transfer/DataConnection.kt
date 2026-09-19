package org.filezilla.ftp.transfer

import org.filezilla.ftp.protocol.Capability
import org.filezilla.ftp.protocol.CapabilityName
import org.filezilla.ftp.protocol.DataEndpoint
import org.filezilla.ftp.protocol.FtpCommandException
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.FtpReply
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.protocol.PasvResponseParser
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.protocol.TransferMode
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket

/** Raised when the server will not honour the resume offset we asked for. */
class ResumeNotHonouredException(message: String) : IOException(message)

/**
 * One data connection: negotiate the endpoint, send `REST` if resuming, send
 * the transfer command, then hand back a connected socket.
 *
 * Ordering here is load-bearing and follows `CFtpRawTransferOpData`
 * (`rawtransfer.cpp`):
 *
 * ```
 * TYPE -> PASV/EPSV -> REST <offset> -> RETR/STOR/LIST -> (TLS handshake) -> data
 * ```
 *
 * `REST` goes out **before** the transfer command and its reply is checked: a
 * server that does not answer 2xx/3xx to a non-zero `REST` has not accepted
 * the offset, and continuing would silently write the wrong bytes into the
 * middle of the file. FileZilla treats that as an error
 * (`rawtransfer.cpp:209-219`) and so does this.
 *
 * The TLS handshake happens only after the transfer command has been sent,
 * because some servers do not start it before then.
 */
internal class DataConnection(
    private val control: FtpControlConnection,
    private val capabilities: ServerCapabilities,
    private val logger: FtpLogger,
) : Closeable {

    // Volatile because [close] is now also called from another thread -- it is
    // how a caller ends a read that would otherwise wait out the socket's
    // timeout; see [TransferAbort]. Without it that thread could read a stale
    // null and close nothing, and the abort would silently do nothing at all.
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var listener: ServerSocket? = null

    /**
     * Opens a data connection and issues [transferCommand].
     *
     * @param resumeOffset byte offset to restart at; 0 means no `REST`.
     * @return the connected data socket, ready to read or write.
     */
    fun open(transferCommand: String, resumeOffset: Long): Socket {
        return if (control.settings.transferMode == TransferMode.ACTIVE) {
            openActive(transferCommand, resumeOffset)
        } else {
            openPassive(transferCommand, resumeOffset)
        }
    }

    /**
     * The usual case: the server names an address and the client connects to
     * it, which is the only thing that works from behind NAT.
     */
    private fun openPassive(transferCommand: String, resumeOffset: Long): Socket {
        val endpoint = negotiatePassiveEndpoint()
        logger.log(LogLevel.DEBUG, "Data connection to ${endpoint.host}:${endpoint.port}")

        val plain = Socket()
        plain.connect(
            InetSocketAddress(endpoint.host, endpoint.port),
            control.settings.connectTimeoutMillis,
        )
        plain.soTimeout = control.settings.readTimeoutMillis
        val wrapped = wrap(plain)
        socket = wrapped

        try {
            sendRestAndCommand(transferCommand, resumeOffset)
            handshake(wrapped)
            return wrapped
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /**
     * Active mode: the client listens and the server connects back.
     *
     * The order is the mirror image of passive and it matters. The listening
     * socket has to exist before `PORT`/`EPRT` names its port, and the accept
     * has to come *after* the transfer command, because the server does not
     * connect until it has one. Accepting earlier would simply block.
     *
     * This needs the server to be able to reach the client, so on a phone it
     * is for a server on the same network. A carrier NAT will not forward the
     * inbound connection, and the symptom is a transfer command that succeeds
     * followed by an accept that times out -- which is what the error below
     * says, rather than leaving the user with a bare timeout.
     */
    private fun openActive(transferCommand: String, resumeOffset: Long): Socket {
        val listener = ServerSocket(0, 1, control.localAddress)
        listener.soTimeout = control.settings.connectTimeoutMillis
        this.listener = listener

        try {
            val local = control.localAddress
            val port = listener.localPort
            logger.log(LogLevel.DEBUG, "Listening for the data connection on ${local.hostAddress}:$port")

            // EPRT carries the address family explicitly and is the only one
            // that can express IPv6; PORT is the older form every server takes.
            val useEprt = local.address.size == 16
            val reply = if (useEprt) {
                control.send("EPRT |2|${local.hostAddress}|$port|")
            } else {
                val octets = local.address.joinToString(",") { (it.toInt() and 0xFF).toString() }
                control.send("PORT $octets,${port shr 8},${port and 0xFF}")
            }
            if (!reply.isSuccess) {
                throw FtpCommandException(
                    reply,
                    "server refused the active-mode data port: ${reply.raw}",
                )
            }

            sendRestAndCommand(transferCommand, resumeOffset)

            val plain = try {
                listener.accept()
            } catch (e: IOException) {
                throw IOException(
                    "the server did not connect back for the data transfer. Active mode needs " +
                        "the server to be able to reach this device; on mobile data, or behind " +
                        "a router without a forwarded port, use passive mode.",
                    e,
                )
            }
            plain.soTimeout = control.settings.readTimeoutMillis
            val wrapped = wrap(plain)
            socket = wrapped
            handshake(wrapped)
            return wrapped
        } catch (e: Throwable) {
            close()
            throw e
        } finally {
            runCatching { listener.close() }
            this.listener = null
        }
    }

    private fun wrap(plain: Socket): Socket = if (control.isDataProtected) {
        control.tlsFactory.wrapDataChannel(
            plain = plain,
            controlHost = control.settings.host,
            controlAddress = control.peerAddress,
            controlPort = control.settings.port,
            reuseControlSession = true,
        )
    } else {
        plain
    }

    private fun sendRestAndCommand(transferCommand: String, resumeOffset: Long) {
        if (resumeOffset > 0) {
            val rest = control.send("REST $resumeOffset")
            if (!rest.isSuccess) {
                throw ResumeNotHonouredException(
                    "server refused REST $resumeOffset: ${rest.raw}",
                )
            }
        }

        val pre = control.send(transferCommand)
        // 1yz is the expected preliminary reply; a few broken servers omit
        // it and answer 2yz/3yz straight away (rawtransfer.cpp:224-227).
        if (!pre.isPositivePreliminary && !pre.isSuccess) {
            throw FtpCommandException(pre, "transfer command refused: ${pre.raw}")
        }
    }

    private fun handshake(wrapped: Socket) {
        if (wrapped !is SSLSocket) return
        // Must follow the transfer command, and resumes the control session --
        // servers with require_ssl_reuse answer 522 here if it did not.
        wrapped.startHandshake()
        capabilities.set(
            control.settings.serverKey,
            CapabilityName.TLS_RESUMPTION,
            Capability.YES,
        )
    }

    /**
     * Reads the reply that closes the transfer. Returns the final reply, which
     * is 226/250 on success.
     */
    fun finish(): FtpReply {
        val reply = control.readReply()
        if (!reply.isSuccess) {
            throw FtpCommandException(reply, "transfer failed: ${reply.raw}")
        }
        return reply
    }

    private fun negotiatePassiveEndpoint(): DataEndpoint {
        val useEpsv = capabilities.get(control.settings.serverKey, CapabilityName.EPSV_COMMAND) ==
            Capability.YES && control.peerAddress.address.size == 16

        if (useEpsv) {
            val reply = control.send("EPSV")
            if (reply.isSuccess) {
                PasvResponseParser.parseEpsv(reply.raw, control.peerHost)?.let { return it }
                logger.log(LogLevel.DEBUG, "Could not parse EPSV reply, falling back to PASV")
            }
        }

        val reply = control.send("PASV")
        if (!reply.isSuccess) throw FtpCommandException(reply, "PASV failed: ${reply.raw}")

        val endpoint = PasvResponseParser.parsePasv(
            reply = reply.raw,
            peerHost = control.peerHost,
            fallbackMode = control.settings.pasvFallbackMode,
        ) ?: throw FtpCommandException(reply, "could not parse PASV reply: ${reply.raw}")

        if (endpoint.addressSubstituted) {
            logger.log(
                LogLevel.STATUS,
                "Server sent passive reply with unroutable address. Using server address instead.",
            )
        }
        return endpoint
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        runCatching { listener?.close() }
        listener = null
    }
}
