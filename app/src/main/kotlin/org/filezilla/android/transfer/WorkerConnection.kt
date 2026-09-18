package org.filezilla.android.transfer

import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.transfer.ControlConnections
import java.io.Closeable

/**
 * One queue worker's control connection, kept open between transfers.
 *
 * Measured against a server 50 ms away, a queue of twenty small files spends
 * roughly three times as long opening connections as it does moving bytes:
 * every file cost a TCP handshake, a banner, `USER`, `PASS` and `FEAT`, twice
 * over, because the size probe opened a connection of its own. Holding one
 * connection across the queue removes almost all of that.
 *
 * Not thread-safe, and not meant to be: one of these belongs to one worker,
 * which runs one transfer at a time. Two workers hold two of them, because a
 * single FTP control connection cannot carry two transfers at once.
 */
class WorkerConnection(
    private val capabilities: ServerCapabilities,
    private val logger: FtpLogger,
    private val now: () -> Long = System::currentTimeMillis,
) : ControlConnections, Closeable {

    private var connection: FtpControlConnection? = null

    /** What the held connection was opened for; a different server needs a new one. */
    private var openFor: FtpSettings? = null

    private var lastUsedAt = 0L

    /** Which server the next [acquire] is for. Set before each transfer. */
    var settings: FtpSettings? = null

    override fun acquire(): FtpControlConnection {
        val wanted = settings ?: error("no server set for this worker")
        val held = connection

        if (held != null && canReuse(openFor, wanted, now() - lastUsedAt)) {
            lastUsedAt = now()
            return held
        }

        // Either nothing is held, it belongs to another server, or it has sat
        // long enough that the server has probably dropped it. Reusing a
        // connection the server has closed costs a failed attempt and a
        // backoff, which is worse than opening one.
        discard()
        val fresh = FtpControlConnection(wanted, capabilities, logger)
        fresh.connect()
        fresh.login()
        connection = fresh
        openFor = wanted
        lastUsedAt = now()
        return fresh
    }

    override fun release(connection: FtpControlConnection, reusable: Boolean) {
        lastUsedAt = now()
        if (reusable) return
        // The attempt ended badly, and the usual reason is that this
        // connection died. Keeping it would hand the next transfer a socket
        // that is going to fail.
        discard()
    }

    private fun discard() {
        connection?.let { held ->
            runCatching { held.close() }
            logger.log(LogLevel.DEBUG, "Closed a worker's control connection")
        }
        connection = null
        openFor = null
    }

    override fun close() = discard()

}

/**
 * How long a held connection may sit unused before it is opened again.
 *
 * Long enough to cover the gap between two files in a queue, short enough that
 * a queue which stalled does not come back to a socket the server closed
 * minutes ago. FileZilla's own idle timeout default is 20 seconds of silence
 * (`engine_options.cpp:19`); servers are usually more generous, but a
 * connection is only worth keeping while reusing it beats opening one.
 */
const val CONNECTION_IDLE_LIMIT_MILLIS = 30_000L

/**
 * Whether a held connection can carry the next transfer.
 *
 * Top-level and tested because both wrong answers cost something real. Reusing
 * a connection opened for a different server would send one server's
 * credentials' session at another's files. Reusing one the server has long
 * since dropped costs a failed attempt and a backoff, which is slower than
 * simply opening a connection.
 *
 * @param heldFor what the held connection was opened for, or null if none is held.
 */
fun canReuse(heldFor: FtpSettings?, wanted: FtpSettings, idleMillis: Long): Boolean =
    heldFor == wanted && idleMillis <= CONNECTION_IDLE_LIMIT_MILLIS
