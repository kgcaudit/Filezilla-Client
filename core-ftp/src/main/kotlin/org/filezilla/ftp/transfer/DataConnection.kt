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
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
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

    private var socket: Socket? = null

    /**
     * Opens a data connection and issues [transferCommand].
     *
     * @param resumeOffset byte offset to restart at; 0 means no `REST`.
     * @return the connected data socket, ready to read or write.
     */
    fun open(transferCommand: String, resumeOffset: Long): Socket {
        val endpoint = negotiatePassiveEndpoint()
        logger.log(LogLevel.DEBUG, "Data connection to ${endpoint.host}:${endpoint.port}")

        val plain = Socket()
        plain.connect(
            InetSocketAddress(endpoint.host, endpoint.port),
            control.settings.connectTimeoutMillis,
        )
        plain.soTimeout = control.settings.readTimeoutMillis

        val wrapped: Socket = if (control.isDataProtected) {
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
        socket = wrapped

        try {
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

            if (wrapped is SSLSocket) {
                // Must follow the transfer command, and resumes the control
                // session -- servers with require_ssl_reuse answer 522 here if
                // it did not.
                wrapped.startHandshake()
                capabilities.set(
                    control.settings.serverKey,
                    CapabilityName.TLS_RESUMPTION,
                    Capability.YES,
                )
            }
            return wrapped
        } catch (e: Throwable) {
            close()
            throw e
        }
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
    }
}
