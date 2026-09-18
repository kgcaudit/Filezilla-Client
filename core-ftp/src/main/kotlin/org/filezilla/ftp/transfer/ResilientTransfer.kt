package org.filezilla.ftp.transfer

import org.filezilla.ftp.io.TransferReader
import org.filezilla.ftp.io.TransferWriter
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.protocol.ServerCapabilities

/** Result of a transfer that may have taken several attempts. */
data class ResilientOutcome(
    /** The outcome of the attempt that finally succeeded. */
    val outcome: TransferOutcome,
    /** How many attempts it took, counting the successful one. */
    val attempts: Int,
    /** Bytes moved across every attempt, including those that were lost. */
    val bytesAcrossAttempts: Long,
)

/**
 * Runs a transfer, reconnecting and resuming when the connection dies.
 *
 * This is the half of network-change recovery that lives in the engine. On a
 * phone the usual cause of a failed transfer is not the server but the network
 * moving underneath it -- Wi-Fi to mobile data, a lift, a tunnel -- and the
 * correct response is to reconnect and carry on from where the partial file
 * ends, not to start again.
 *
 * Each attempt gets a fresh control connection, because a connection that
 * dropped cannot be reused. The [ServerCapabilities] cache is shared across
 * attempts on purpose: the 2 GB/4 GB resume probe costs an extra data
 * connection, and repeating it on every reconnect would be exactly the wrong
 * thing to do on a flaky link.
 *
 * The writer and reader arrive as factories rather than instances because each
 * attempt needs its own. A writer is closed when its attempt ends, and closing
 * is what truncates the partial file back to the bytes actually received --
 * which is what makes the next attempt resume from the right offset.
 */
class ResilientTransfer(
    private val settings: FtpSettings,
    private val capabilities: ServerCapabilities = ServerCapabilities(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val logger: FtpLogger = FtpLogger.NONE,
    /** Overridable so tests do not actually wait out the backoff. */
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) {

    /**
     * Downloads [remoteFile], reconnecting and resuming as needed.
     *
     * [writerFactory] comes last so it reads as a trailing lambda, and is a
     * factory rather than an instance because each attempt needs its own
     * writer -- closing one is what truncates the partial file back to the
     * bytes actually received.
     */
    fun download(
        remoteFile: String,
        binary: Boolean = true,
        progress: TransferProgressListener? = null,
        writerFactory: () -> TransferWriter,
    ): ResilientOutcome = withRetries(progress) { engine, tracked ->
        engine.download(remoteFile, writerFactory(), resume = true, binary = binary, progress = tracked)
    }

    /** Uploads to [remoteFile], reconnecting and resuming as needed. */
    fun upload(
        remoteFile: String,
        binary: Boolean = true,
        progress: TransferProgressListener? = null,
        readerFactory: () -> TransferReader,
    ): ResilientOutcome = withRetries(progress) { engine, tracked ->
        engine.upload(remoteFile, readerFactory(), resume = true, binary = binary, progress = tracked)
    }

    private fun withRetries(
        progress: TransferProgressListener?,
        attemptBody: (FtpTransferEngine, TransferProgressListener?) -> TransferOutcome,
    ): ResilientOutcome {
        var attempt = 0
        var bytesAcrossAttempts = 0L

        while (true) {
            attempt++
            val delay = retryPolicy.delayBeforeAttempt(attempt)
            if (delay > 0) {
                logger.log(
                    LogLevel.STATUS,
                    "Delaying reconnect for ${delay / 1000} second(s) after a failed attempt...",
                )
                sleep(delay)
            }

            // Tracks what this attempt moved, so bytes lost to a dropped
            // connection still show up in the total. On a metered connection
            // that number is the cost of the retry, and worth reporting.
            var movedThisAttempt = 0L
            val counting = TransferProgressListener { transferred, resumeOffset, totalSize ->
                movedThisAttempt = transferred
                progress?.onProgress(transferred, resumeOffset, totalSize)
            }

            try {
                val outcome = FtpControlConnection(settings, capabilities, logger).use { control ->
                    control.connect()
                    control.login()
                    attemptBody(FtpTransferEngine(control, capabilities, logger), counting)
                }
                bytesAcrossAttempts += outcome.bytesTransferred
                if (attempt > 1) {
                    logger.log(LogLevel.STATUS, "Transfer completed on attempt $attempt")
                }
                return ResilientOutcome(outcome, attempt, bytesAcrossAttempts)
            } catch (e: Throwable) {
                bytesAcrossAttempts += movedThisAttempt
                if (!retryPolicy.shouldRetry(e, attempt)) {
                    logger.log(LogLevel.ERROR, "Transfer failed after $attempt attempt(s): ${e.message}")
                    throw e
                }
                logger.log(
                    LogLevel.STATUS,
                    "Attempt $attempt failed (${e.message}); reconnecting to resume",
                )
            }
        }
    }
}
