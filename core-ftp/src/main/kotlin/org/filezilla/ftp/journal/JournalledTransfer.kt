package org.filezilla.ftp.journal

import org.filezilla.ftp.io.TransferWriter
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.transfer.ControlConnections
import org.filezilla.ftp.transfer.ResilientTransfer
import org.filezilla.ftp.transfer.RetryPolicy
import org.filezilla.ftp.transfer.TransferProgressListener

/**
 * Runs a transfer while keeping a [TransferJournal] up to date, so it can be
 * picked up again after the process is killed.
 *
 * The journal is written as bytes arrive rather than only at the end. That
 * costs a little, and it is the whole point: Android kills a backgrounded app
 * without warning, and a journal updated only on success would record nothing
 * about the transfer that was actually in flight.
 *
 * Before resuming, [ResumeSafety] checks the recorded offset against what the
 * server says now. A transfer picked up hours later may be pointing at a file
 * that has since been replaced, and no one is watching to answer a prompt
 * about it.
 */
class JournalledTransfer(
    private val journal: TransferJournal,
    private val settings: FtpSettings,
    private val capabilities: ServerCapabilities = ServerCapabilities(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val logger: FtpLogger = FtpLogger.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How often to write progress to the journal, in bytes. */
    private val journalEveryBytes: Long = 1L shl 20,
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    /** Passed straight to [ResilientTransfer]; see [ControlConnections]. */
    private val connections: ControlConnections =
        ControlConnections.perAttempt(settings, capabilities, logger),
) {

    /**
     * Downloads the file named by [record], resuming where it is safe to.
     *
     * [writerFactory] receives the offset the transfer will start from, so the
     * caller can discard a partial file that [ResumeSafety] rejected.
     */
    fun download(
        record: TransferRecord,
        currentRemote: RemoteFingerprint,
        localPartialSize: Long?,
        writerFactory: (startOffset: Long) -> TransferWriter,
        progress: TransferProgressListener? = null,
    ): TransferRecord {
        val decision = ResumeSafety.decide(record, currentRemote, localPartialSize)
        when (decision) {
            is ResumeDecision.AlreadyComplete -> {
                logger.log(LogLevel.STATUS, "${record.remotePath} is already complete")
                return finish(record, currentRemote, record.bytesTransferred)
            }

            is ResumeDecision.RestartFromZero ->
                logger.log(
                    LogLevel.STATUS,
                    "Restarting ${record.remotePath} from the beginning: ${decision.reason}",
                )

            is ResumeDecision.ResumeFrom ->
                logger.log(
                    LogLevel.STATUS,
                    "Resuming ${record.remotePath} from ${decision.offset} bytes",
                )
        }

        val startOffset = when (decision) {
            is ResumeDecision.ResumeFrom -> decision.offset
            else -> 0L
        }

        var running = record.copy(
            state = TransferState.RUNNING,
            bytesTransferred = startOffset,
            fingerprint = currentRemote,
            attempts = record.attempts + 1,
            lastError = null,
            updatedAtMillis = clock(),
        )
        journal.put(running)

        var lastJournalled = startOffset
        val recording = TransferProgressListener { transferred, resumeOffset, totalSize ->
            val position = resumeOffset + transferred
            if (position - lastJournalled >= journalEveryBytes) {
                lastJournalled = position
                running = running.copy(
                    bytesTransferred = position,
                    totalBytes = totalSize ?: running.totalBytes,
                    updatedAtMillis = clock(),
                )
                journal.put(running)
            }
            progress?.onProgress(transferred, resumeOffset, totalSize)
        }

        return try {
            val result = ResilientTransfer(
                settings = settings,
                capabilities = capabilities,
                retryPolicy = retryPolicy,
                logger = logger,
                sleep = sleep,
                connections = connections,
            ).download(
                remoteFile = record.remotePath,
                progress = recording,
                // The decision above, not the partial file's length, settles
                // where this starts: after a restart the file may hold bytes
                // that were never accounted for, or belong to a file the
                // server has since replaced.
                forcedResumeOffset = startOffset,
            ) { writerFactory(startOffset) }
            val total = result.outcome.totalSize
                ?: (result.outcome.resumeOffset + result.outcome.bytesTransferred)
            finish(running.copy(totalBytes = total), currentRemote, total)
        } catch (e: Exception) {
            // INTERRUPTED rather than FAILED: the partial file is still a valid
            // prefix, so this is worth picking up again later.
            val interrupted = running.copy(
                state = TransferState.INTERRUPTED,
                lastError = e.message ?: e.javaClass.simpleName,
                updatedAtMillis = clock(),
            )
            journal.put(interrupted)
            logger.log(LogLevel.ERROR, "${record.remotePath} interrupted: ${interrupted.lastError}")
            throw e
        }
    }

    private fun finish(
        record: TransferRecord,
        fingerprint: RemoteFingerprint,
        bytes: Long,
    ): TransferRecord {
        val done = record.copy(
            state = TransferState.COMPLETED,
            bytesTransferred = bytes,
            fingerprint = fingerprint,
            updatedAtMillis = clock(),
        )
        journal.put(done)
        return done
    }
}
