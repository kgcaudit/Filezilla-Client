package org.filezilla.android.transfer

import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.filezilla.android.data.AppDatabase
import org.filezilla.android.data.PasswordCipher
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.storage.ConflictChoice
import org.filezilla.android.storage.DownloadDestination
import org.filezilla.android.storage.PartialFiles
import java.io.File
import org.filezilla.android.storage.SafStorage
import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.journal.JournalledTransfer
import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferJournal
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.transfer.ResilientTransfer
import org.filezilla.ftp.transfer.RetryPolicy
import org.filezilla.ftp.transfer.TransferAbort
import org.filezilla.ftp.transfer.TransferProgressListener
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What the notification and the queue screen show about the running transfer. */
data class ActiveProgress(
    val id: String,
    val remotePath: String,
    val direction: TransferDirection,
    val bytes: Long,
    val totalBytes: Long?,
    /** Smoothed speed, or null until there is enough of the transfer to say. */
    val bytesPerSecond: Long? = null,
    /** When this run started, so a caller showing one transfer picks the same one. */
    val startedAtMillis: Long = 0,
    /**
     * When bytes last arrived.
     *
     * The screen needs it to tell a transfer that is moving from one that has
     * gone quiet: progress is pushed when bytes arrive, so a dead connection
     * leaves the card exactly as it was, still claiming a speed. See
     * [isStalled].
     */
    val updatedAtMillis: Long = 0,
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
    private val networkGate: TransferGate,
    private val passwords: PasswordCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Shared across transfers on purpose. The 2 GB/4 GB resume probe costs an
     * extra data connection, and repeating it per transfer -- or per reconnect
     * -- is exactly the wrong thing to do on a flaky mobile link.
     */
    private val capabilities = ServerCapabilities()

    /**
     * Every transfer currently moving, keyed by id.
     *
     * A map rather than a single slot because two run at once now. The screen
     * matches each card against it, so a card only shows a live figure when
     * that transfer is one of the ones running.
     */
    private val activeState = MutableStateFlow<Map<String, ActiveProgress>>(emptyMap())
    val activeTransfers: StateFlow<Map<String, ActiveProgress>> = activeState.asStateFlow()

    /**
     * One of the running transfers, for callers that can only show one.
     *
     * The notification is the reason this exists: it has room for a single
     * progress bar, and the transfer that started first is the one furthest
     * along, so it is the least surprising one to put there.
     */
    val active: Flow<ActiveProgress?> = activeState.map(::foremost)

    /** The same choice, for callers that need it right now rather than as a flow. */
    fun foremostActive(): ActiveProgress? = foremost(activeState.value)

    private fun foremost(running: Map<String, ActiveProgress>): ActiveProgress? =
        running.values.minByOrNull { it.startedAtMillis }

    /** How many transfers are moving at once. */
    fun runningCount(): Int = activeState.value.size

    private val waitingState = MutableStateFlow(0)

    /** How many transfers the queue still has to get to after the running one. */
    val waitingCount: StateFlow<Int> = waitingState.asStateFlow()

    @Volatile
    private var stopRequested = false

    /** Delivers a pause to the transfer thread; see [PauseSignal]. */
    private val pauseSignal = PauseSignal()

    /** Speed of each running transfer, so two do not share one measurement. */
    private val rates = ConcurrentHashMap<String, TransferRate>()

    /**
     * The handle on each running transfer's socket; see [TransferAbort].
     *
     * [PauseSignal] alone was not enough, and this is the bug it exists to
     * fix: a pause is only noticed in the progress callback, which the engine
     * calls as bytes arrive. A transfer whose network has gone receives no
     * bytes, so it is precisely when the user presses pause that pause did
     * nothing -- the card sat at the same byte count and the same stale speed
     * until the socket timed out. Closing the socket from here ends the read
     * at once.
     */
    private val aborts = ConcurrentHashMap<String, TransferAbort>()

    /**
     * Closes aborted sockets off the caller's thread.
     *
     * Because closing a TLS socket writes a close_notify, and the callers here
     * are the connectivity callback and the Wi-Fi-only switch -- the switch
     * runs on the main thread, where Android treats a write as an error. The
     * abort catches whatever the close throws, so without this the failure
     * would be silent and the transfer would go back to being frozen with a
     * dead button, which is the whole thing this was meant to fix.
     */
    private val aborter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "transfer-abort").apply { isDaemon = true }
    }

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
        onConflict: ConflictChoice = ConflictChoice.DEFAULT,
        /** Half of a move: the file on the server goes once this has landed. */
        removeSource: Boolean = false,
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
                destination = DownloadDestination(destinationTree, subPath, onConflict).encode(),
                state = TransferState.PENDING,
                totalBytes = totalBytes,
                updatedAtMillis = System.currentTimeMillis(),
                removeSourceWhenDone = removeSource,
            ),
        )
        id
    }

    suspend fun enqueueUpload(
        site: SiteEntity,
        remotePath: String,
        source: Uri,
        totalBytes: Long?,
        /**
         * Replace a file already on the server rather than resuming it.
         *
         * Carried in the destination column, which an upload does not
         * otherwise use, so this needs no new column and records written
         * before uploads could replace anything read back as false.
         */
        overwrite: Boolean = false,
        /** Half of a move: the file on the phone goes once this has landed. */
        removeSource: Boolean = false,
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
                destination = if (overwrite) OVERWRITE_MARKER else null,
                state = TransferState.PENDING,
                totalBytes = totalBytes,
                updatedAtMillis = System.currentTimeMillis(),
                removeSourceWhenDone = removeSource,
            ),
        )
        id
    }

    // ----------------------------------------------------------- queue control

    /**
     * Anything the queue still has work to do for.
     *
     * Work held back for the network counts: the service has to stay alive to
     * notice Wi-Fi coming back, or nothing would ever start it again.
     */
    suspend fun hasRunnableWork(): Boolean = withContext(io) {
        claimable().isNotEmpty() ||
            journal.all().any { it.state == TransferState.WAITING_FOR_NETWORK }
    }

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
    /**
     * What one run of the queue got through.
     *
     * Returned rather than left in the journal for a caller to work out,
     * because "since this run started" is a fact only the run has: the
     * journal keeps every transfer the app has ever done, and counting the
     * completed ones would count last week's.
     */
    data class QueueOutcome(val completed: Int, val failed: Int) {
        val isEmpty: Boolean get() = completed == 0 && failed == 0

        companion object {
            val NOTHING = QueueOutcome(0, 0)
        }
    }

    suspend fun runQueue(): QueueOutcome = coroutineScope {
        stopRequested = false
        val startedAt = System.currentTimeMillis()
        withContext(io) {
            // A download whose bytes are all here but which never reached the
            // user's folder is finished as far as the journal is concerned.
            // Publishing it first means a crash between the last byte and the
            // copy costs a file copy, not a re-download.
            runInterruptible { republishUnpublished() }

            // Nothing is running yet, so anything still marked RUNNING was
            // left by a process that died. Clearing them here is what lets
            // claim() tell a leftover from a record a live worker holds.
            runInterruptible { database.transfers().releaseStaleClaims(System.currentTimeMillis()) }

            // Work held back for a network the user ruled out is work again
            // if the network is now fine. Without this a queue parked when the
            // process was killed would come back to a working connection and
            // sit there: nothing held for the network is claimable, and only a
            // worker coming out of a wait released it -- a wait this run never
            // entered.
            runInterruptible { if (networkGate.isAllowed) releaseFromNetworkWait() }

            val workers = List(CONCURRENT_TRANSFERS) { launch { worker() } }
            workers.joinAll()

            activeState.value = emptyMap()
            waitingState.value = 0
            val finished = journal.all()
            partials.pruneOrphans(finished.map { it.id }.toSet())

            // Only what this run touched. A record that finished earlier
            // keeps its old timestamp, so the clock is what separates this
            // run's work from every run before it.
            val mine = finished.filter { it.updatedAtMillis >= startedAt }
            QueueOutcome(
                completed = mine.count { it.state == TransferState.COMPLETED },
                failed = mine.count { it.state == TransferState.FAILED },
            )
        }
    }

    /**
     * One queue worker: claims a transfer, runs it, repeats.
     *
     * Its connection outlives the transfers it runs, which is where most of
     * the speed on a queue of small files comes from. The worker owns it
     * outright -- one FTP control connection cannot carry two transfers -- and
     * closes it on the way out.
     */
    private suspend fun worker() {
        WorkerConnection(capabilities, log).use { connection ->
            while (!stopRequested) {
                // Before claiming anything, not after: starting a transfer on
                // a network the user ruled out and stopping it a moment later
                // still spends their data.
                if (!networkGate.isAllowed) {
                    // Every worker parks and every worker releases. Tying
                    // either to one worker's index looked tidier and stranded
                    // the queue: the worker holding the transfer when the
                    // network went is whichever one claimed it, and the other
                    // may have exited long before -- so the one that woke was
                    // not the one allowed to put the work back.
                    //
                    // Both are idempotent, and claiming is atomic, so there is
                    // nothing to coordinate.
                    parkForNetwork()
                    runInterruptible { networkGate.awaitAllowed() }
                    if (stopRequested) break
                    releaseFromNetworkWait()
                    continue
                }
                val record = claimNext() ?: break
                waitingState.value = claimable().size
                // runInterruptible so that cancelling this coroutine -- which
                // is what stopping the service does -- interrupts a socket
                // parked on a read, instead of waiting out its timeout.
                runInterruptible { runOne(record, connection) }
            }
        }
    }

    /** Transfers no worker has taken yet, oldest first. */
    private fun claimable(): List<TransferRecord> =
        journal.all()
            .filter { it.state == TransferState.PENDING || it.state == TransferState.INTERRUPTED }
            .sortedBy { it.updatedAtMillis }

    /**
     * Takes the next transfer for this worker, or null when there is none.
     *
     * Reads a candidate then claims it conditionally, and moves on when the
     * claim loses. With two workers the read and the write cannot be one
     * decision -- between them, the other worker may have taken the record.
     */
    private fun claimNext(): TransferRecord? {
        while (!stopRequested) {
            val candidate = claimable().firstOrNull() ?: return null
            val taken = database.transfers().claim(candidate.id, System.currentTimeMillis()) == 1
            if (taken) return journal.get(candidate.id) ?: candidate
        }
        return null
    }

    private fun runOne(record: TransferRecord, connection: WorkerConnection) {
        val site = database.sites().byEndpoint(record.host, record.port, record.user)
        if (site == null) {
            fail(record, "the saved server for ${record.user}@${record.host} is gone")
            return
        }

        rates[record.id] = TransferRate()
        val abort = TransferAbort()
        aborts[record.id] = abort
        // A stop asked for between the claim and here would otherwise be
        // delivered to nothing, and the transfer would run on regardless.
        if (pauseSignal.isRequested(record.id)) abort.abortAndStop()
        publishProgress(
            ActiveProgress(
                id = record.id,
                remotePath = record.remotePath,
                direction = record.direction,
                bytes = record.bytesTransferred,
                totalBytes = record.totalBytes,
                startedAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )

        val settings = site.toSettings(passwords)
        connection.settings = settings
        var stopped: StopReason? = null
        try {
            when (record.direction) {
                TransferDirection.DOWNLOAD -> runDownload(record, settings, connection, abort)
                TransferDirection.UPLOAD -> runUpload(record, settings, connection, abort)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            markInterrupted(record, "stopped")
            throw e
        } catch (e: Exception) {
            // The reason is asked of the signal rather than read off the
            // exception, because a stop delivered by closing the socket
            // arrives as an ordinary IOException. Only a stop the transfer
            // noticed itself carries a TransferPausedException, and that is
            // now the rarer of the two.
            val reason = (e as? TransferPausedException)?.reason ?: pauseSignal.reasonFor(record.id)
            if (reason == null) {
                // JournalledTransfer has already written INTERRUPTED for a
                // download; for anything it did not reach, record it here so
                // the queue does not spin on the same record.
                markInterrupted(record, e.message ?: e.javaClass.simpleName)
            } else {
                // Written after JournalledTransfer has had its say: it marks
                // the record INTERRUPTED on the way out, and this has to be
                // what survives, or the queue would pick the transfer straight
                // back up.
                stopped = reason
                when (reason) {
                    StopReason.USER -> markPaused(record)
                    StopReason.NETWORK -> markWaitingForNetwork(record)
                    StopReason.CANCEL -> discard(record)
                }
            }
        } finally {
            // A cancel that arrived while the transfer was finishing never
            // reached a progress callback and closed a socket that was already
            // done with, so nothing threw. Honour it here rather than leaving
            // a record the user has already dismissed -- but only if the catch
            // above did not already do it.
            if (stopped == null && pauseSignal.reasonFor(record.id) == StopReason.CANCEL) {
                discard(record)
            }
            rates.remove(record.id)
            aborts.remove(record.id)
            activeState.value = activeState.value - record.id
            pauseSignal.clear(record.id)
        }
    }

    /**
     * Throws a transfer away, record and bytes.
     *
     * Run on the transfer's own thread as it unwinds, not from the UI. The
     * bug this shape exists to fix is that removing the record from outside
     * did nothing: [JournalledTransfer] keeps its own copy and writes it back
     * every megabyte, so a record deleted underneath it came straight back --
     * and the transfer carried on regardless, because nothing had told it to
     * stop.
     */
    private fun discard(record: TransferRecord) {
        journal.remove(record.id)
        partials.delete(record.id)
        log.log(LogLevel.STATUS, "${record.remotePath} cancelled")
    }

    /**
     * Runs [block] on the worker's connection, opening one if needed.
     *
     * The connection is handed back as unusable when [block] throws, because
     * the usual reason a command throws is that the connection died; keeping
     * it would hand the transfer that follows a socket already gone.
     */
    private fun <T> onWorkerConnection(
        connection: WorkerConnection,
        block: (org.filezilla.ftp.protocol.FtpControlConnection) -> T,
    ): T {
        val control = connection.acquire()
        var reusable = false
        try {
            return block(control).also { reusable = true }
        } finally {
            connection.release(control, reusable)
        }
    }

    /** Puts one transfer's live figures where the screen can see them. */
    private fun publishProgress(progress: ActiveProgress) {
        activeState.value = activeState.value + (progress.id to progress)
    }

    // ------------------------------------------------------------- downloading

    private fun runDownload(
        record: TransferRecord,
        settings: FtpSettings,
        connection: WorkerConnection,
        abort: TransferAbort,
    ) {
        // On the worker's own connection, which the transfer is about to use
        // anyway. This used to open a second connection, so every file in the
        // queue paid two logins -- on small files, most of the time spent.
        val fingerprint: RemoteFingerprint = onWorkerConnection(connection) { control ->
            fingerprintOf(control, capabilities, log, record.remotePath)
        }

        val partial = partials.forTransfer(record.id)
        val finished = JournalledTransfer(
            journal = journal,
            settings = settings,
            capabilities = capabilities,
            retryPolicy = RetryPolicy(maxAttempts = settings.maxRetries),
            logger = log,
            sleep = { millis -> networkGate.waitBeforeRetry(millis) { abort.isStopped } },
            connections = connection,
            abort = abort,
        ).download(
            record = record,
            currentRemote = fingerprint,
            localPartialSize = partials.sizeOf(record.id),
            writerFactory = { partial.asTransferWriter() },
            progress = progressListener(record),
        )

        val delivered = publish(finished, partial)

        // Only once the file is in the user's own folder. The partial lives
        // in app-private storage, which they cannot reach, so removing the
        // server's copy while the file was only there would be a move into
        // nowhere -- and "keep the one I already have" is not delivery
        // either: those bytes were dropped on purpose, and the server's copy
        // is then the only one left.
        if (MovedSource.mayRemoveRemote(finished, delivered)) {
            removeRemoteSource(finished, connection)
        }
    }

    /**
     * Takes the server's copy away once the phone has it.
     *
     * On the worker's own connection, which is still in hand and already
     * logged in. As with the upload, a refusal is reported and the queue
     * carries on: the file has arrived, and a server that will not let go of
     * it is not a reason to stop moving everything else.
     */
    private fun removeRemoteSource(record: TransferRecord, connection: WorkerConnection) {
        val name = record.remotePath.substringAfterLast('/')
        val removed = runCatching {
            onWorkerConnection(connection) { control ->
                org.filezilla.ftp.protocol.FtpFileOperations(control).deleteFile(record.remotePath)
            }
        }
        if (removed.isSuccess) {
            log.log(LogLevel.STATUS, "Moved $name from the server; removed the copy there")
        } else {
            log.log(
                LogLevel.ERROR,
                "Fetched $name but could not remove it from the server " +
                    "(${removed.exceptionOrNull()?.message})",
            )
        }
    }

    /**
     * Copies a finished download into the user's folder.
     *
     * Failure here is not failure of the transfer: the bytes are on the device
     * and cost data to fetch. The record stays COMPLETED and the partial file
     * is kept, so the copy is retried on the next run rather than the file
     * being downloaded again.
     */
    private fun publish(record: TransferRecord, partial: java.io.File): MovedSource.Delivery {
        val destination = record.destination?.let(DownloadDestination::decode)
        if (destination == null) {
            log.log(LogLevel.ERROR, "${record.remotePath} has no destination folder; keeping it on the device")
            return MovedSource.Delivery.NOT_YET
        }
        if (!partial.isFile) return MovedSource.Delivery.NOT_YET

        val name = record.remotePath.substringAfterLast('/').ifEmpty { record.id }
        try {
            val saved = storage.publish(partial, destination, name)
            partials.delete(record.id)
            if (saved == null) {
                // The user chose to keep the copy already in the folder. The
                // bytes are dropped rather than left on the device: nothing
                // will ever publish them, and they would sit in app storage
                // until the app was uninstalled.
                log.log(LogLevel.STATUS, "$name is already in the chosen folder; kept the existing file")
                return MovedSource.Delivery.KEPT_EXISTING
            }
            log.log(LogLevel.STATUS, "Saved $name to $saved")
            return MovedSource.Delivery.SAVED
        } catch (e: IOException) {
            log.log(
                LogLevel.ERROR,
                "Downloaded $name but could not save it to the chosen folder (${e.message}); " +
                    "it is kept on the device and will be saved on the next run",
            )
            return MovedSource.Delivery.NOT_YET
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
    private fun runUpload(
        record: TransferRecord,
        settings: FtpSettings,
        connection: WorkerConnection,
        abort: TransferAbort,
    ) {
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
            sleep = { millis -> networkGate.waitBeforeRetry(millis) { abort.isStopped } },
            connections = connection,
            abort = abort,
        ).upload(
            remoteFile = record.remotePath,
            // Resume is what would otherwise treat a file already on the
            // server as a half-sent copy of this one and append to it,
            // splicing two different files together.
            resume = uploadResumes(record.destination),
            progress = progressListener(record),
        ) { storage.readerFor(source) }

        running = running.copy(
            state = TransferState.COMPLETED,
            bytesTransferred = result.outcome.resumeOffset + result.outcome.bytesTransferred,
            totalBytes = result.outcome.totalSize ?: running.totalBytes,
            updatedAtMillis = System.currentTimeMillis(),
        )
        journal.put(running)

        // A file has appeared on the server, so whatever this app was
        // holding about the folder it landed in no longer describes it. The
        // queue has its own connections and never borrows a browse session,
        // so the write counter above cannot see this one.
        listings.forgetServer(record.host, record.port, record.user)

        // After the record says COMPLETED and not before. If the process
        // dies between the two, the file is still on the phone and the
        // record still says it arrived, which is the harmless way round.
        if (MovedSource.isDue(running)) removeLocalSource(running)
    }

    /**
     * Takes the phone's copy away once the server has it.
     *
     * A failure here is logged and nothing more. The bytes are on the
     * server, so the move has happened in the sense that matters; what is
     * left is a file the user can delete, which is a great deal better than
     * a queue that stops because one file was in use.
     */
    private fun removeLocalSource(record: TransferRecord) {
        val file = MovedSource.localFileOf(record.localPath) ?: return
        val gone = runCatching { file.delete() }.getOrDefault(false)
        if (gone) {
            log.log(LogLevel.STATUS, "Moved ${file.name} to the server; removed the copy here")
        } else {
            log.log(
                LogLevel.ERROR,
                "Sent ${file.name} to the server but could not remove it from this phone",
            )
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun progressListener(record: TransferRecord) =
        TransferProgressListener { transferred, resumeOffset, totalSize ->
            // The one place a running transfer can be stopped promptly. The
            // engine calls this every 64 KB and does not catch what it throws.
            pauseSignal.stopIfRequested(record.id)
            // Measured on what has moved this run, not on the resume offset:
            // bytes fetched yesterday did not arrive at today's speed.
            val rate = rates[record.id]
            rate?.update(transferred)
            publishProgress(
                ActiveProgress(
                    id = record.id,
                    remotePath = record.remotePath,
                    direction = record.direction,
                    bytes = resumeOffset + transferred,
                    totalBytes = totalSize ?: record.totalBytes,
                    bytesPerSecond = rate?.bytesPerSecond,
                    startedAtMillis = activeState.value[record.id]?.startedAtMillis ?: 0,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
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

    /**
     * Records that a transfer is held back for the network.
     *
     * The attempt count is deliberately left alone. Waiting for Wi-Fi is not
     * a failed attempt, and counting it as one would walk the transfer toward
     * FAILED for doing exactly what the user asked.
     */
    private fun markWaitingForNetwork(record: TransferRecord) {
        val current = journal.get(record.id) ?: record
        if (current.isTerminal || current.state == TransferState.PAUSED) return
        journal.put(
            current.copy(
                state = TransferState.WAITING_FOR_NETWORK,
                lastError = null,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    /** Moves everything the queue could run into the network wait. */
    private fun parkForNetwork() {
        for (record in journal.all()) {
            if (waitsForNetwork(record.state)) markWaitingForNetwork(record)
        }
        activeState.value = emptyMap()
        waitingState.value = 0
    }

    /** Puts them back once an allowed network is here. */
    private fun releaseFromNetworkWait() {
        for (record in journal.all()) {
            if (!startsAgainWhenNetworkReturns(record.state)) continue
            journal.put(
                record.copy(
                    state = TransferState.PENDING,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Stops whatever is running because the network stopped being one the
     * user allows. Called from the network callback, not the transfer thread.
     */
    fun onNetworkDisallowed() {
        for (id in activeState.value.keys) stopRunning(id, StopReason.NETWORK)
    }

    /**
     * Called when the phone swapped one usable network for another.
     *
     * Nothing is paused: the queue may still transfer, so the transfer should
     * carry on -- but not on the sockets it has, which were bound to the
     * network that went and will read nothing until they time out. Closing
     * them turns a silent twenty-second stall into an immediate reconnect and
     * resume, which is what the user was promised.
     */
    fun onNetworkChanged() {
        if (aborts.isEmpty()) return
        log.log(LogLevel.STATUS, "The network changed; reconnecting what was in flight")
        for (abort in aborts.values) aborter.execute { abort.abortAndRetry() }
    }

    /**
     * Asks the transfer with [id] to stop, and makes sure it can hear.
     *
     * Both halves matter. The signal is what tells the queue afterwards why
     * the transfer ended -- paused, cancelled, or waiting for the network --
     * and the abort is what ends it now rather than whenever the next byte
     * happens to arrive.
     */
    private fun stopRunning(id: String, reason: StopReason) {
        pauseSignal.request(id, reason)
        aborts[id]?.let { abort ->
            // Recorded here so the retry loop sees it at once, and closed on
            // another thread because closing writes; see [aborter].
            abort.stop()
            aborter.execute { abort.abortAndStop() }
        }
    }

    /** True when the queue is holding work back for the network. */
    suspend fun hasNetworkHeldWork(): Boolean = withContext(io) {
        journal.all().any { it.state == TransferState.WAITING_FOR_NETWORK }
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
        if (activeState.value.containsKey(id)) {
            stopRunning(id, StopReason.USER)
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

    /**
     * Cancels a transfer, whether or not it is the one running.
     *
     * A running transfer cannot simply be removed from the journal, for the
     * same reason a running transfer cannot simply be marked PAUSED: the copy
     * [JournalledTransfer] holds is written back every megabyte and puts the
     * record straight back. So the running one is asked to stop through
     * [PauseSignal] and throws itself away as it unwinds; see [discard].
     */
    suspend fun cancel(id: String) = withContext(io) {
        if (activeState.value.containsKey(id)) {
            stopRunning(id, StopReason.CANCEL)
            return@withContext
        }
        journal.remove(id)
        partials.delete(id)
        Unit
    }

    /**
     * Stops everything that is going, and holds it.
     *
     * One at a time through [pause], deliberately: a running transfer
     * cannot simply be marked PAUSED -- the copy JournalledTransfer holds
     * is written back every megabyte and would put the record straight
     * back -- and pause already knows that. Doing it again in bulk here
     * would be a second implementation of the awkward part.
     */
    suspend fun pauseAll() = withContext(io) {
        for (record in journal.all()) {
            if (record.state == TransferState.RUNNING ||
                record.state == TransferState.PENDING ||
                record.state == TransferState.INTERRUPTED ||
                record.state == TransferState.WAITING_FOR_NETWORK
            ) {
                pause(record.id)
            }
        }
    }

    /**
     * Puts everything outstanding back on its feet.
     *
     * Failures included, and that is the point: [resume] gives a record a
     * fresh retry budget, so this is the one action that can set a list of
     * nothing but failures going again. Starting the service is the
     * caller's job -- it needs a context, and this layer has none.
     */
    suspend fun startAll() = withContext(io) {
        for (record in journal.all()) {
            if (record.state == TransferState.PAUSED ||
                record.state == TransferState.FAILED ||
                record.state == TransferState.INTERRUPTED ||
                record.state == TransferState.WAITING_FOR_NETWORK
            ) {
                resume(record.id)
            }
        }
    }

    /** Takes the failures out of the list. Their partial bytes go with them. */
    suspend fun clearFailed() = withContext(io) {
        for (record in journal.all().filter { it.state == TransferState.FAILED }) {
            journal.remove(record.id)
            partials.delete(record.id)
        }
    }

    /**
     * Empties the list, stopping whatever is running on the way.
     *
     * Through [cancel] for the same reason pauseAll goes through pause:
     * a running transfer throws itself away as it unwinds, and the journal
     * cannot simply be emptied underneath it.
     */
    suspend fun clearAll() = withContext(io) {
        for (record in journal.all()) cancel(record.id)
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

    private val browseConnections = BrowseConnections.forServers(capabilities, passwords, log)

    /**
     * What each server last said about a folder, so walking back up a tree
     * costs nothing. Shared: written by whoever listed, emptied here by
     * whoever wrote.
     */
    val listings = RemoteListings()

    /**
     * A blocking browse, run so that coroutine cancellation interrupts the
     * socket rather than leaving it parked on a read.
     */
    suspend fun <T> browse(site: SiteEntity, block: (FtpSession) -> T): T = withContext(io) {
        // Through the pool, so a folder tap is one command rather than a
        // whole login. See BrowseConnections for what makes reusing one
        // safe; the short version is that it is held one caller at a time
        // and retried once when the server has silently hung up.
        browseConnections.withSession(site) { session ->
            // Every remote change this app makes outside the transfer queue
            // goes through a session borrowed here, so this is the one place
            // that has to notice one. The count is read either side rather
            // than the operations being listed, so a write added to
            // FtpSession later is covered without anything being remembered
            // here -- and in a finally, because a delete that threw half way
            // through has still deleted something.
            val before = session.writes
            try {
                runInterruptible { block(session) }
            } finally {
                if (session.writes != before) listings.forget(site)
            }
        }
    }

    /**
     * Fetches one file into [into] so it can be looked at, and nothing else.
     *
     * Not through the queue, deliberately. The queue is for things the user
     * asked to keep: it journals them, survives a restart, retries across
     * sessions and finally delivers into a folder they chose. None of that
     * is wanted here -- this is a copy of something still on the server,
     * wanted now, and abandoned the moment they back out. A record of it in
     * the transfer list would be a row the user did not put there.
     *
     * On its own connection rather than the pooled browse one, which is
     * held by a single caller at a time: a film coming down it would freeze
     * the pane it was tapped from.
     */
    suspend fun fetchForViewing(
        site: SiteEntity,
        remotePath: String,
        into: File,
        abort: TransferAbort,
        progress: (bytes: Long, total: Long?) -> Unit,
    ): Unit = withContext(io) {
        val settings = site.toSettings(passwords)
        WorkerConnection(capabilities, log).use { connection ->
            connection.settings = settings
            runInterruptible {
                ResilientTransfer(
                    settings = settings,
                    capabilities = capabilities,
                    retryPolicy = RetryPolicy(maxAttempts = settings.maxRetries),
                    logger = log,
                    sleep = { millis -> networkGate.waitBeforeRetry(millis) { abort.isStopped } },
                    connections = connection,
                    abort = abort,
                ).download(
                    remoteFile = remotePath,
                    // Always from the beginning. A half-fetched copy from a
                    // cancelled look is not something to resume onto: the
                    // file may have changed since, and the only thing worse
                    // than fetching it again is opening two halves of two
                    // different files spliced together.
                    forcedResumeOffset = 0,
                    progress = { transferred, _, total -> progress(transferred, total) },
                    writerFactory = { into.asTransferWriter() },
                )
            }
        }
        Unit
    }

    /** Lets go of a server's kept connection, for one edited or deleted. */
    suspend fun forget(site: SiteEntity) {
        browseConnections.close(site)
        listings.forget(site)
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
 * How many transfers run at once.
 *
 * Two, which is FileZilla's own default and its recommendation for a reason:
 * servers limit how many connections one address may hold -- Pure-FTPd allows
 * 15 by default, and NAS boxes are routinely configured far lower -- and a
 * client that opens more gets `421` rather than more speed.
 *
 * Measured against a server 50 ms away, a queue of twenty small files took
 * 5.33s on one connection and 2.86s on two. Four took it to 1.69s, but each
 * connection past the second buys less and risks more: the step from four to
 * eight doubled the connections for 43%.
 */
const val CONCURRENT_TRANSFERS = 2

/**
 * Whether a transfer that has just failed should be given up on.
 *
 * Top-level and tested, because getting it wrong in the lenient direction is
 * not a cosmetic bug: an INTERRUPTED record is immediately runnable again, so
 * a transfer that never gives up is a loop that reconnects and re-fails for as
 * long as the service lives, spending the user's data every time round.
 */
fun hasExhaustedQueuePasses(attempts: Int): Boolean = attempts >= MAX_QUEUE_PASSES

/**
 * What an upload record's destination column says when it is a replacement.
 *
 * That column is the download's folder and an upload has no use for it, so it
 * carries this instead -- no new column, and records written before uploads
 * could replace anything read back as "not a replacement".
 */
const val OVERWRITE_MARKER = "overwrite"

/**
 * Whether an upload may pick up where a file on the server left off.
 *
 * The decision that made overwriting dangerous, so it is a function with a
 * name rather than a comparison buried in the call. Resume treats a file
 * already there as a half-sent copy of this one and appends the rest -- which
 * for a file the user chose to replace splices two different files together
 * and leaves no sign that it did.
 */
fun uploadResumes(destination: String?): Boolean = destination != OVERWRITE_MARKER
