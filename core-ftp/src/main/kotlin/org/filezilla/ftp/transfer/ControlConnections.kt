package org.filezilla.ftp.transfer

import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities

/**
 * Where a transfer attempt gets its control connection.
 *
 * Exists so that a caller running many small transfers can keep one connection
 * open across them. A fresh connection costs a TCP handshake, a banner, `USER`,
 * `PASS` and `FEAT` -- and a TLS handshake on top for FTPS -- which is several
 * round trips before a single byte of the file moves. On a queue of small
 * files that is most of the time spent.
 *
 * The engine does not decide whether to reuse: it asks for a connection and
 * says afterwards whether the connection is still worth keeping. Reuse is the
 * caller's policy, because only the caller knows how many transfers are
 * coming and whether they are all for the same server.
 */
interface ControlConnections {

    /** A connected, logged-in connection for one attempt. */
    fun acquire(): FtpControlConnection

    /**
     * Hands the connection back.
     *
     * @param reusable false when the attempt ended badly. A connection whose
     *   transfer threw is not to be trusted -- the usual reason a transfer
     *   throws is that the connection died -- so the implementation closes it
     *   rather than offering it to the next attempt.
     */
    fun release(connection: FtpControlConnection, reusable: Boolean)

    companion object {
        /**
         * One connection per attempt, closed when the attempt ends.
         *
         * What the engine did before this interface existed, and still the
         * default: a caller that has not thought about reuse gets the
         * behaviour that is always correct.
         */
        fun perAttempt(
            settings: FtpSettings,
            capabilities: ServerCapabilities,
            logger: FtpLogger = FtpLogger.NONE,
        ): ControlConnections = object : ControlConnections {

            override fun acquire(): FtpControlConnection =
                FtpControlConnection(settings, capabilities, logger).also {
                    it.connect()
                    it.login()
                }

            override fun release(connection: FtpControlConnection, reusable: Boolean) {
                runCatching { connection.close() }
            }
        }
    }
}
