package org.filezilla.ftp.sftp

import org.filezilla.ftp.io.TransferReader
import org.filezilla.ftp.io.TransferWriter
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.transfer.ResilientOutcome
import org.filezilla.ftp.transfer.RetryPolicy
import org.filezilla.ftp.transfer.TransferAbort
import org.filezilla.ftp.transfer.TransferAbortedException
import org.filezilla.ftp.transfer.TransferProgressListener

/**
 * Runs an SFTP transfer, reconnecting and resuming when the connection dies --
 * the SFTP counterpart of [org.filezilla.ftp.transfer.ResilientTransfer].
 *
 * A separate class rather than a branch inside that one because the FTP version
 * is welded to the FTP engine and its control connections, and the whole point
 * of this build is that SFTP does not disturb any of that. What the two share
 * is not code but shape, and the pieces that shape rests on -- [RetryPolicy],
 * [TransferAbort], resume-by-offset -- are already protocol-neutral, so this is
 * the same retry loop over the SFTP engine.
 *
 * A fresh engine per attempt: a failed attempt's connection is exactly the
 * thing that failed, and SFTP has no separate control connection to keep alive
 * across files the way FTP does, so there is nothing to pool here.
 */
class SftpResilientTransfer(
    private val settings: SftpSettings,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val logger: FtpLogger = FtpLogger.NONE,
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val abort: TransferAbort? = null,
) {

    /**
     * Downloads [remoteFile], reconnecting and resuming as needed.
     *
     * [forcedResumeOffset] pins the first attempt to an offset a caller has
     * already checked against the server (see
     * [org.filezilla.ftp.journal.ResumeSafety]); after a dropped connection the
     * partial file is the evidence again, so later attempts let the engine
     * derive the offset from it.
     */
    fun download(
        remoteFile: String,
        forcedResumeOffset: Long?,
        progress: TransferProgressListener?,
        writerFactory: () -> TransferWriter,
    ): ResilientOutcome = withRetries { engine, attempt ->
        engine.download(
            remoteFile = remoteFile,
            writer = writerFactory(),
            progress = progress,
            forcedResumeOffset = forcedResumeOffset.takeIf { attempt == 1 },
        )
    }

    /** Uploads to [remoteFile], reconnecting and resuming as needed. */
    fun upload(
        remoteFile: String,
        resume: Boolean,
        progress: TransferProgressListener?,
        readerFactory: () -> TransferReader,
    ): ResilientOutcome = withRetries { engine, _ ->
        engine.upload(remoteFile, readerFactory(), resume = resume, progress = progress)
    }

    private fun withRetries(
        attemptBody: (SftpEngine, Int) -> org.filezilla.ftp.transfer.TransferOutcome,
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
            if (abort?.isStopped == true) throw TransferAbortedException()

            val engine = SftpEngine(settings, logger, abort)
            try {
                engine.connect()
                val outcome = attemptBody(engine, attempt)
                bytesAcrossAttempts += outcome.bytesTransferred
                if (attempt > 1) logger.log(LogLevel.STATUS, "Transfer completed on attempt $attempt")
                return ResilientOutcome(outcome, attempt, bytesAcrossAttempts)
            } catch (e: Throwable) {
                if (abort?.isStopped == true) {
                    // The caller stopped it on purpose; a closed channel looks
                    // like a dropped connection, so this is checked before the
                    // retry policy rather than left to it -- otherwise the
                    // pause would answer itself by reconnecting.
                    logger.log(LogLevel.STATUS, "Transfer stopped on request after $attempt attempt(s)")
                    throw e
                }
                if (!retryPolicy.shouldRetry(e, attempt)) {
                    logger.log(LogLevel.ERROR, "Transfer failed after $attempt attempt(s): ${e.message}")
                    throw e
                }
                logger.log(LogLevel.STATUS, "Attempt $attempt failed (${e.message}); reconnecting to resume")
            } finally {
                engine.close()
            }
        }
    }
}
