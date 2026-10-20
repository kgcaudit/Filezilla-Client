package org.filezilla.ftp.webdav

import org.filezilla.ftp.io.TransferReader
import org.filezilla.ftp.io.TransferWriter
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.transfer.ResilientOutcome
import org.filezilla.ftp.transfer.RetryPolicy
import org.filezilla.ftp.transfer.TransferAbort
import org.filezilla.ftp.transfer.TransferAbortedException
import org.filezilla.ftp.transfer.TransferOutcome
import org.filezilla.ftp.transfer.TransferProgressListener

/**
 * Runs a WebDAV transfer, retrying a dropped connection -- the WebDAV
 * counterpart of [org.filezilla.ftp.sftp.SftpResilientTransfer], the same retry
 * loop over the WebDAV engine.
 *
 * A fresh engine per attempt, as for SFTP: a WebDAV engine holds no connection
 * of its own worth keeping (the JDK HTTP client pools underneath), so there is
 * nothing to carry across a retry. A download resumes from the partial file's
 * size on a later attempt; an upload starts over, since WebDAV cannot append.
 */
class WebDavResilientTransfer(
    private val settings: WebDavSettings,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val logger: FtpLogger = FtpLogger.NONE,
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val abort: TransferAbort? = null,
) {

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
            // Only the first attempt trusts the caller's offset; later ones let
            // the partial file on disk say how far it got.
            forcedResumeOffset = forcedResumeOffset.takeIf { attempt == 1 },
        )
    }

    fun upload(
        remoteFile: String,
        resume: Boolean,
        progress: TransferProgressListener?,
        readerFactory: () -> TransferReader,
    ): ResilientOutcome = withRetries { engine, _ ->
        engine.upload(remoteFile, readerFactory(), resume = resume, progress = progress)
    }

    private fun withRetries(attemptBody: (WebDavEngine, Int) -> TransferOutcome): ResilientOutcome {
        var attempt = 0
        var bytesAcrossAttempts = 0L
        while (true) {
            attempt++
            val delay = retryPolicy.delayBeforeAttempt(attempt)
            if (delay > 0) {
                logger.log(LogLevel.STATUS, "Delaying retry for ${delay / 1000} second(s) after a failed attempt...")
                sleep(delay)
            }
            if (abort?.isStopped == true) throw TransferAbortedException()

            val engine = WebDavEngine(settings)
            try {
                engine.connect()
                val outcome = attemptBody(engine, attempt)
                bytesAcrossAttempts += outcome.bytesTransferred
                if (attempt > 1) logger.log(LogLevel.STATUS, "Transfer completed on attempt $attempt")
                return ResilientOutcome(outcome, attempt, bytesAcrossAttempts)
            } catch (e: Throwable) {
                if (abort?.isStopped == true) {
                    logger.log(LogLevel.STATUS, "Transfer stopped on request after $attempt attempt(s)")
                    throw e
                }
                if (!retryPolicy.shouldRetry(e, attempt)) {
                    logger.log(LogLevel.ERROR, "Transfer failed after $attempt attempt(s): ${e.message}")
                    throw e
                }
                logger.log(LogLevel.STATUS, "Attempt $attempt failed (${e.message}); retrying")
            } finally {
                engine.close()
            }
        }
    }
}
