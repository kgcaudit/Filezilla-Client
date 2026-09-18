package org.filezilla.ftp.protocol

import org.filezilla.ftp.net.TlsFactory
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.Writer
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Raised when the server answers a command with an unexpected reply code. */
class FtpCommandException(val reply: FtpReply, message: String) : IOException(message)

/**
 * The FTP control connection: one socket, commands out, replies in.
 *
 * Login is a port of the ordering in `engine/ftp/logon.cpp`: `AUTH TLS` before
 * credentials, then `FEAT` to learn what the server can do, then `PBSZ 0` and
 * `PROT P` to secure the data channel. What `FEAT` reports is fed into
 * [ServerCapabilities], which is what later decides between `REST`+`STOR` and
 * `APPE` for a resumed upload.
 */
class FtpControlConnection(
    val settings: FtpSettings,
    private val capabilities: ServerCapabilities,
    private val logger: FtpLogger = FtpLogger.NONE,
) : Closeable {

    private var plainSocket: Socket? = null
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: Writer? = null

    /** Held for the whole connection so the data channel can resume its session. */
    val tlsFactory = TlsFactory(trustAllCertificates = settings.trustAllCertificates)

    var isSecure: Boolean = false
        private set

    /** Set once `PROT P` succeeded, meaning data connections must be TLS. */
    var isDataProtected: Boolean = false
        private set

    /**
     * Set once `TYPE` was negotiated, so it is not resent for every transfer.
     * Mirrors `CFtpControlSocket::m_lastTypeBinary` (-1 unknown, 1 I, 0 A).
     */
    private var lastTypeBinary: Int = -1

    /**
     * Charset negotiated for the control connection, which applies to
     * directory listings on the data channel too.
     */
    var charset: Charset = StandardCharsets.UTF_8
        private set

    /** The address the control connection is actually talking to. */
    lateinit var peerAddress: InetAddress
        private set

    val peerHost: String get() = peerAddress.hostAddress

    // ---------------------------------------------------------------- connect

    fun connect() {
        logger.log(LogLevel.STATUS, "Connecting to ${settings.host}:${settings.port}...")
        val plain = Socket()
        plain.connect(InetSocketAddress(settings.host, settings.port), settings.connectTimeoutMillis)
        plain.soTimeout = settings.readTimeoutMillis
        plain.tcpNoDelay = true
        plainSocket = plain
        peerAddress = plain.inetAddress

        if (settings.security == FtpSecurity.IMPLICIT_TLS) {
            val tls = tlsFactory.upgradeControl(plain, settings.host, settings.port)
            adopt(tls)
            isSecure = true
            logger.log(LogLevel.STATUS, "TLS established (implicit), waiting for welcome message...")
            expect(readReply(), 220)
        } else {
            adopt(plain)
            logger.log(LogLevel.STATUS, "Connection established, waiting for welcome message...")
            expect(readReply(), 220)

            if (settings.security == FtpSecurity.EXPLICIT_TLS) {
                startExplicitTls()
            }
        }
    }

    private fun startExplicitTls() {
        val reply = send("AUTH TLS")
        if (!reply.isSuccess) {
            capabilities.set(settings.serverKey, CapabilityName.AUTH_TLS_COMMAND, Capability.NO)
            throw FtpCommandException(reply, "server refused AUTH TLS: ${reply.raw}")
        }
        capabilities.set(settings.serverKey, CapabilityName.AUTH_TLS_COMMAND, Capability.YES)

        logger.log(LogLevel.STATUS, "Initializing TLS...")
        val tls = tlsFactory.upgradeControl(plainSocket!!, settings.host, settings.port)
        adopt(tls)
        isSecure = true
        logger.log(
            LogLevel.STATUS,
            "TLS established: ${tls.session.protocol} / ${tls.session.cipherSuite}",
        )
    }

    fun login() {
        val userReply = send("USER ${settings.user}")
        when {
            userReply.code == 230 -> Unit
            userReply.isPositiveIntermediate -> expect(send("PASS ${settings.password}"), 230)
            else -> throw FtpCommandException(userReply, "login failed: ${userReply.raw}")
        }
        logger.log(LogLevel.STATUS, "Logged in")

        queryFeatures()

        if (isSecure) {
            // RFC 4217: PBSZ must precede PROT, and is always 0 for TLS.
            send("PBSZ 0")
            val prot = send("PROT P")
            if (!prot.isSuccess) {
                throw FtpCommandException(prot, "server refused PROT P: ${prot.raw}")
            }
            isDataProtected = true
        }

        if (capabilities.get(settings.serverKey, CapabilityName.UTF8_COMMAND) == Capability.YES) {
            send("OPTS UTF8 ON")
            charset = StandardCharsets.UTF_8
            rebindStreams()
        }
    }

    private fun queryFeatures() {
        val reply = send("FEAT")
        if (!reply.isSuccess) {
            capabilities.set(settings.serverKey, CapabilityName.FEAT_COMMAND, Capability.NO)
            return
        }
        // Drop the surrounding "211-Features:" and "211 End" lines.
        val body = reply.lines.drop(1).dropLast(1)
        capabilities.applyFeatures(settings.serverKey, body)
    }

    // --------------------------------------------------------------- commands

    /** Sends [command] and returns the reply, logging both. */
    fun send(command: String): FtpReply {
        val w = writer ?: throw IOException("not connected")
        val redacted = if (command.startsWith("PASS ")) "PASS ***" else command
        logger.log(LogLevel.COMMAND, redacted)
        w.write(command)
        w.write("\r\n")
        w.flush()
        return readReply()
    }

    /**
     * Reads one reply, joining the lines of a multi-line reply as RFC 959
     * section 4.2 defines it.
     */
    fun readReply(): FtpReply {
        val r = reader ?: throw IOException("not connected")
        val first = r.readLine() ?: throw IOException("Connection closed by server")
        logger.log(LogLevel.REPLY, first)

        val lines = mutableListOf(first)
        if (FtpReply.isMultilineStart(first)) {
            val tag = first.take(3)
            while (true) {
                val line = r.readLine() ?: throw IOException("Connection closed by server")
                logger.log(LogLevel.REPLY, line)
                lines += line
                if (FtpReply.isMultilineEnd(line, tag)) break
            }
        }
        return FtpReply.of(lines)
    }

    /**
     * Sets the transfer type, skipping the command when it is already right.
     * Port of the `rawtransfer_init` / `rawtransfer_type` states
     * (`rawtransfer.cpp:23-67`).
     */
    fun setTransferType(binary: Boolean) {
        val wanted = if (binary) 1 else 0
        if (lastTypeBinary == wanted) return
        lastTypeBinary = -1
        val reply = send(if (binary) "TYPE I" else "TYPE A")
        if (!reply.isSuccess) throw FtpCommandException(reply, "TYPE failed: ${reply.raw}")
        lastTypeBinary = wanted
    }

    // ----------------------------------------------------------------- plumbing

    private fun adopt(s: Socket) {
        socket = s
        rebindStreams()
    }

    private fun rebindStreams() {
        val s = socket ?: throw IOException("not connected")
        reader = BufferedReader(InputStreamReader(s.getInputStream(), charset))
        writer = OutputStreamWriter(s.getOutputStream(), charset)
    }

    private fun expect(reply: FtpReply, code: Int): FtpReply {
        if (reply.code != code) {
            throw FtpCommandException(reply, "expected $code but got ${reply.code}: ${reply.raw}")
        }
        return reply
    }

    override fun close() {
        runCatching { if (socket?.isClosed == false) send("QUIT") }
        runCatching { socket?.close() }
        runCatching { plainSocket?.close() }
        socket = null
        plainSocket = null
        reader = null
        writer = null
    }
}
