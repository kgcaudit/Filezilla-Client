package org.filezilla.android.transfer

import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.filezilla.android.data.AppDatabase
import org.filezilla.android.data.PasswordCipher
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.storage.DownloadDestination
import org.filezilla.android.storage.PartialFiles
import org.filezilla.android.storage.SafStorage
import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.journal.JournalledTransfer
import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferJournal
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.transfer.ResilientTransfer
import org.filezilla.ftp.transfer.RetryPolicy
import org.filezilla.ftp.transfer.TransferProgressListener
import java.io.IOException
import java.util.UUID

/** What the notification and the queue screen show about the running transfer. */
data class ActiveProgress(
    val id: String,
    val remotePath: String,
    val direction: TransferDirection,
    val bytes: Long,
    val totalBytes: Long?,
)

/**
 * Runs the transfer queue.
 *
 * Every decision about *whether* a partial transfer can be resumed, and from
 * what offset, belongs to `:core-ftp` -- [org.filezilla.ftp.journal.ResumeSafety]
 * makes it and [JournalledTransfer] applies it, both verified against a live
 * server. Nothing here second-guesses that. What this class does is the part
 * that needs Android: find the credentials, ask the server what the file looks
 * like now, hand over somewhere to write, and put the finished file where the
 * user asked.
 *
 * One transfer at a time. Two concurrent transfers on a phone share the same
 * radio and the same metered connection, and the result is that both take
 * longer and both are at risk when the network moves.
 */
class TransferManager(
    private val database: AppDatabase,
    private val journal: TransferJournal,
    private val partials: PartialFiles,
    private val storage: SafStorage,
    private val log: AppLog,
    private val networkGate: NetworkGate,
    private val passwords: PasswordCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Shared across transfers on purpose. The 2 GB/4 GB resume probe costs an
     * extra data connection, and repeating it per transfer -- or per reconnect
     * -- is exactly the wrong thing to do on a flaky mobile link.
     */
    private val capabilities = ServerCapabilities()

    private val activeState = MutableStateFlow<ActiveProgress?>(null)
    val active: StateFlow<ActiveProgress?> = activeState.asStateFlow()

    private val waitingState = MutableStateFlow(0)

    /** How many transfers the queue still has to get to after the running one. */
    val waitingCount: StateFlow<Int> = waitingState.asStateFlow()

    @Volatile
    private var stopRequested = false

    /** Delivers a pause to the transfer thread; see [PauseSignal]. */
    private val pauseSignal = PauseSignal()

    /** Which transfer a thread is actually running, or null between records. */
    @Volatile
    private var activeId: String? = null

    fun observeTransfers(): Flow<List<TransferRecord>> =
        database.transfers().observeAll().map { rows -> rows.map { it.toRecord() } }

    // ------------------------------------------------------------- enqueueing

    /**
     * @param subPath folders to mirror inside the chosen one. Empty for a file
     *   the user picked directly; the path below the folder they picked when a
     *   whole folder is being downloaded.
     */
    suspend fun enqueueDownload(
        site: SiteEntity,
        remotePath: String,
        totalBytes: Long?,
        destinationTree: Uri,
        subPath: List<String> = emptyList(),
    ): String = withContext(io) {
        val id = UUID.randomUUID().toString()
        journal.put(
            TransferRecord(
                id = id,
                direction = TransferDirection.DOWNLOAD,
                host = site.host,
                port = site.port,
                user = site.user,
                remotePath = remotePath,
                localPath = partials.forTransfer(id).absolutePath,
                // The folder, not a file in it. The document is created only
                // once the transfer is complete, so a half-finished download
                // never appears in the user's folder looking openable.
                destination = DownloadDestination(destinationTree, subPath).encode(),
                state = TransferState.PENDING,
                totalBytes = totalBytes,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        id
    }

    suspend fun enqueueUpload(
        site: SiteEntity,
        remotePath: String,
        source: Uri,
        totalBytes: Long?,
    ): String = withContext(io) {
        val id = UUID.randomUUID().toString()
        journal.put(
            TransferRecord(
                id = id,
                direction = TransferDirection.UPLOAD,
                host = site.host,
                port = site.port,
                user = site.user,
                remotePath = remotePath,
                localPath = source.toString(),
                destination = null,
                state = TransferState.PENDING,
                totalBytes = totalBytes,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        id
    }

    // ----------------------------------------------------------- queue control

    /** Anything the queue still has work to do for. */
    suspend fun hasRunnableWork(): Boolean = withContext(io) { nextRunnable() != null }

    fun requestStop() {
        stopRequested = true
    }

    /**
     * Works through the queue until it is empty or a stop is requested.
     *
     * Cancelling the coroutine interrupts the transfer thread; the partial
     * file and its journalled offset survive, which is what lets the next run
     * pick it up.
     */
    suspend fun runQueue() {
        stopRequested = false
        withContext(io) {
            // A download whose bytes are all here but which never reached the
            // user's folder is finished as far as the journal is concerned.
            // Publishing it first means a crash between the last byte and the
            // copy costs a file copy, not a re-download.
            runInterruptible { republishUnpublished() }

            while (!stopRequested) {
                val record = nextRunnable() ?: break
                waitingState.value = (journal.resumable().size - 1).coerceAtLeast(0)
                // runInterruptible so that cancelling this coroutine -- which
                // is what stopping the service does -- interrupts a socket
                // parked on a read, instead of waiting out its timeout.
                runInterruptible { runOne(record) }
            }
            activeState.value = null
            waitingState.value = 0
            partials.pruneOrphans(journal.all().map { it.id }.toSet())
        }
    }

    private fun nextRunnable(): TransferRecord? =
        journal.resumable().minByOrNull { it.updatedAtMillis }

    private fun runOne(record: TransferRecord) {
        val site = database.sites().byEndpoint(record.host, record.port, record.user)
        if (site == null) {
            fail(record, "the saved server for ${record.user}@${record.host} is gone")
            return
        }

        activeState.value = ActiveProgress(
            id = record.id,
            remotePath = record.remotePath,
            direction = record.direction,
            bytes = record.bytesTransferred,
            totalBytes = record.totalBytes,
        )

        activeId = record.id
        try {
            when (record.direction) {
                TransferDirection.DOWNLOAD -> runDownload(record, site)
                TransferDirection.UPLOAD -> runUpload(record, site)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            markInterrupted(record, "stopped")
            throw e
        } catch (e: TransferPausedException) {
            // Written after JournalledTransfer has had its say: it marks the
            // record INTERRUPTED on the way out, and PAUSED has to be what
            // survives, or the queue would pick the transfer straight back up.
            markPaused(record)
        } catch (e: Exception) {
            // JournalledTransfer has already written INTERRUPTED for a
            // download; for anything it did not reach, record it here so the
            // queue does not spin on the same record.
            markInterrupted(record, e.message ?: e.javaClass.simpleName)
        } finally {
            activeId = null
            pauseSignal.clear()
        }
    }

    // ------------------------------------------------------------- downloading

    private fun runDownload(record: TransferRecord, site: SiteEntity) {
        val settings = site.toSettings(passwords)

        // A connection of its own, because the transfer needs the one it
        // opens and this has to happen before it starts.
        val fingerprint: RemoteFingerprint = FtpSession(settings, capabilities, log).use { session ->
            session.connect()
            session.fingerprint(record.remotePath)
        }

        val partial = partials.forTransfer(record.id)
        val finished = JournalledTransfer(
            journal = journal,
            settings = settings,
            capabilities = capabilities,
            retryPolicy = RetryPolicy(maxAttempts = settings.maxRetries),
            logger = log,
            sleep = { millis -> networkGate.waitBeforeRetry(millis) },
        ).download(
            record = record,
            currentRemote = fingerprint,
            localPartialSize = partials.sizeOf(record.id),
            writerFactory = { partial.asTransferWriter() },
            progress = progressListener(record),
        )

        publish(finished, partial)
    }

    /**
     * Copies a finished download into the user's folder.
     *
     * Failure here is not failure of the transfer: the bytes are on the device
     * and cost data to fetch. The record stays COMPLETED and the partial file
     * is kept, so the copy is retried on the next run rather than the file
     * being downloaded again.
     */
    private fun publish(record: TransferRecord, partial: java.io.File) {
        val destination = record.destination?.let(DownloadDestination::decode)
        if (destination == null) {
            log.log(LogLevel.ERROR, "${record.remotePath} has no destination folder; keeping it on the device")
            return
        }
        if (!partial.isFile) return

        val name = record.remotePath.substringAfterLast('/').ifEmpty { record.id }
        try {
            val saved = storage.publish(partial, destination, name)
            partials.delete(record.id)
            log.log(LogLevel.STATUS, "Saved $name to $saved")
        } catch (e: IOException) {
            log.log(
                LogLevel.ERROR,
                "Downloaded $name but could not save it to the chosen folder (${e.message}); " +
                    "it is kept on the device and will be saved on the next run",
            )
        }
    }

    private fun republishUnpublished() {
        for (record in journal.all()) {
            if (record.state != TransferState.COMPLETED) continue
            if (record.direction != TransferDirection.DOWNLOAD) continue
            val partial = partials.forTransfer(record.id)
            if (partial.isFile) publish(record, partial)
        }
    }

    // --------------------------------------------------------------- uploading

    /**
     * Uploads go through [ResilientTransfer] rather than [JournalledTransfer].
     *
     * Not an omission: an upload's resume offset comes from the server's
     * `SIZE`, not from anything recorded here, and the engine already decides
     * between `REST`+`STOR` and `APPE` from what the server advertised. There
     * is no local offset to make safe, so there is no resume-safety decision
     * to apply -- and inventing one here would be exactly the duplication that
     * keeping the logic in `:core-ftp` is meant to avoid. The journal still
     * tracks the record so the queue survives a restart.
     */
    private fun runUpload(record: TransferRecord, site: SiteEntity) {
        val settings = site.toSettings(passwords)
        val source = Uri.parse(record.localPath)
        var running = record.copy(
            state = TransferState.RUNNING,
            attempts = record.attempts + 1,
            lastError = null,
            updatedAtMillis = System.currentTimeMillis(),
        )
        journal.put(running)

        val result = ResilientTransfer(
            settings = settings,
            capabilities = capabilities,
            retryPolicy = RetryPolicy(maxAttempts = settings.maxRetries),
            logger = log,
            sleep = { millis -> networkGate.waitBeforeRetry(millis) },
        ).upload(
            remoteFile = record.remotePath,
            progress = progressListener(record),
        ) { storage.readerFor(source) }

        running = running.copy(
            state = TransferState.COMPLETED,
            bytesTransferred = result.outcome.resumeOffset + result.outcome.bytesTransferred,
            totalBytes = result.outcome.totalSize ?: running.totalBytes,
            updatedAtMillis = System.currentTimeMillis(),
        )
        journal.put(running)
    }

    // ----------------------------------------------------------------- helpers

    private fun progressListener(record: TransferRecord) =
        TransferProgressListener { transferred, resumeOffset, totalSize ->
            // The one place a running transfer can be stopped promptly. The
            // engine calls this every 64 KB and does not catch what it throws.
            pauseSignal.stopIfRequested(record.id)
            activeState.value = ActiveProgress(
                id = record.id,
                remotePath = record.remotePath,
                direction = record.direction,
                bytes = resumeOffset + transferred,
                totalBytes = totalSize ?: record.totalBytes,
            )
        }

    /**
     * Records a failed pass, and gives up once there have been enough of them.
     *
     * [org.filezilla.ftp.transfer.RetryPolicy] has already spent its own
     * retries by the time a transfer throws, so an INTERRUPTED record is
     * immediately runnable again -- and the queue would pick it straight back
     * up, reconnect, fail, and do it again, for as long as the service lives.
     * That is a loop that costs the user data and hammers the server.
     *
     * So a transfer that keeps coming back is marked FAILED and left for the
     * user to restart, which resets the count. The partial file is kept either
     * way: FAILED here means "stop trying on your own", not "throw the bytes
     * away".
     */
    private fun markInterrupted(record: TransferRecord, reason: String) {
        val current = journal.get(record.id) ?: record
        if (current.isTerminal) return
        val exhausted = hasExhaustedQueuePasses(current.attempts)
        journal.put(
            current.copy(
                state = if (exhausted) TransferState.FAILED else TransferState.INTERRUPTED,
                lastError = if (exhausted) "$reason (gave up after ${current.attempts} attempts)" else reason,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Records a pause, keeping the bytes already fetched.
     *
     * `lastError` is cleared: the user asked for this, so showing it as a
     * failure in the queue would be a lie.
     */
    private fun markPaused(record: TransferRecord) {
        val current = journal.get(record.id) ?: record
        if (current.state == TransferState.COMPLETED) return
        journal.put(
            current.copy(
                state = TransferState.PAUSED,
                lastError = null,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        log.log(
            LogLevel.STATUS,
            "${record.remotePath} paused at ${current.bytesTransferred} bytes; " +
                "resuming will not fetch them again",
        )
    }

    private fun fail(record: TransferRecord, reason: String) {
        log.log(LogLevel.ERROR, "${record.remotePath}: $reason")
        journal.put(
            record.copy(
                state = TransferState.FAILED,
                lastError = reason,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    // ------------------------------------------------------- per-record actions

    /**
     * Pauses a transfer, whether or not it is the one currently running.
     *
     * A running transfer cannot simply be marked PAUSED in the journal:
     * JournalledTransfer writes its own RUNNING copy back every megabyte and
     * would erase it. So the running one is asked to stop through
     * [PauseSignal], and the PAUSED record is written by [markPaused] once it
     * has unwound -- after JournalledTransfer has written its INTERRUPTED, so
     * that PAUSED is what survives.
     */
    suspend fun pause(id: String) = withContext(io) {
        if (activeId == id) {
            pauseSignal.request(id)
            return@withContext
        }
        journal.get(id)?.let {
            journal.put(
                it.copy(
                    state = TransferState.PAUSED,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
        Unit
    }

    suspend fun resume(id: String) = withContext(io) {
        journal.get(id)?.let {
            journal.put(
                it.copy(
                    state = TransferState.PENDING,
                    lastError = null,
                    // The attempt count is what the retry policy spends. A
                    // transfer the user restarts by hand starts again with a
                    // full budget, or a failed one could never be retried.
                    attempts = 0,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
        Unit
    }

    suspend fun cancel(id: String) = withContext(io) {
        journal.remove(id)
        partials.delete(id)
    }

    suspend fun clearCompleted() = withContext(io) {
        val completed = journal.all().filter { it.state == TransferState.COMPLETED }
        for (record in completed) {
            // Only once the file has reached the user's folder; otherwise
            // clearing the list would throw away bytes that cost data.
            if (!partials.forTransfer(record.id).isFile) journal.remove(record.id)
        }
        Unit
    }

    /**
     * A blocking browse, run so that coroutine cancellation interrupts the
     * socket rather than leaving it parked on a read.
     */
    suspend fun <T> browse(site: SiteEntity, block: (FtpSession) -> T): T = withContext(io) {
        runInterruptible {
            FtpSession(site.toSettings(passwords), capabilities, log).use { session ->
                session.connect()
                block(session)
            }
        }
    }
}

/**
 * How many times the queue will pick a transfer back up by itself.
 *
 * Each pass is already a full [RetryPolicy] budget of reconnects, so three
 * passes is a lot of trying, not a little.
 */
const val MAX_QUEUE_PASSES = 3

/**
 * Whether a transfer that has just failed should be given up on.
 *
 * Top-level and tested, because getting it wrong in the lenient direction is
 * not a cosmetic bug: an INTERRUPTED record is immediately runnable again, so
 * a transfer that never gives up is a loop that reconnects and re-fails for as
 * long as the service lives, spending the user's data every time round.
 */
fun hasExhaustedQueuePasses(attempts: Int): Boolean = attempts >= MAX_QUEUE_PASSES
