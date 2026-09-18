package org.filezilla.ftp.journal

/** Which way a transfer goes. */
enum class TransferDirection { DOWNLOAD, UPLOAD }

/** Where a queued transfer has got to. */
enum class TransferState {
    /** Queued, not started. */
    PENDING,

    /** A connection is working on it right now. */
    RUNNING,

    /** Stopped by the user, or waiting for a network the user allows. */
    PAUSED,

    /** Stopped by an error that may clear; the partial file is still good. */
    INTERRUPTED,

    COMPLETED,

    /** Stopped by something a retry will not fix. */
    FAILED,
}

/**
 * Enough of a remote file's identity to tell whether it is still the same file.
 *
 * Resuming is only safe if the bytes already fetched are a prefix of what the
 * server would send now. Size alone does not establish that -- a file can be
 * replaced by another of the same length -- so the modification time is carried
 * too, from `MLSD` or `MDTM`.
 */
data class RemoteFingerprint(
    val size: Long?,
    val modifiedMillis: Long?,
) {
    val isUsable: Boolean get() = size != null || modifiedMillis != null
}

/**
 * One transfer as it survives the process being killed.
 *
 * Android will kill a backgrounded app mid-transfer, so the offset to resume
 * from cannot live only in memory, and it cannot be re-derived from the partial
 * file alone either: the file's length says how many bytes are there, not
 * whether they still match the server. [bytesTransferred] and [fingerprint]
 * together are what make a resume after a restart safe rather than hopeful.
 */
data class TransferRecord(
    val id: String,
    val direction: TransferDirection,

    /** Which server, matching [org.filezilla.ftp.protocol.FtpSettings]. */
    val host: String,
    val port: Int,
    val user: String,

    val remotePath: String,

    /**
     * The partial file being written or read. For a download this is the
     * `.part` file in app-private storage, not the user's chosen destination.
     */
    val localPath: String,

    /**
     * Where a completed download is moved to, as an opaque string the platform
     * understands -- on Android a Storage Access Framework document URI. Null
     * for uploads.
     */
    val destination: String? = null,

    val state: TransferState = TransferState.PENDING,

    /** Bytes known to be correctly in place. The resume offset. */
    val bytesTransferred: Long = 0,

    val totalBytes: Long? = null,

    /** What the remote file looked like when those bytes were fetched. */
    val fingerprint: RemoteFingerprint? = null,

    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAtMillis: Long = 0,
) {
    val isTerminal: Boolean get() = state == TransferState.COMPLETED || state == TransferState.FAILED
}

/**
 * Durable store for [TransferRecord]s.
 *
 * Kept as an interface here, with no Android types, so the queue logic can be
 * tested on a plain JVM; the app module supplies a Room-backed implementation.
 * Implementations must be safe to call from several threads.
 */
interface TransferJournal {
    fun put(record: TransferRecord)
    fun get(id: String): TransferRecord?
    fun all(): List<TransferRecord>
    fun remove(id: String)

    /**
     * Transfers that were still in flight and can be picked up again, which
     * after a restart is what a `RUNNING` record means: nothing is running, so
     * the process died holding it.
     */
    fun resumable(): List<TransferRecord> = all().filter {
        it.state == TransferState.RUNNING ||
            it.state == TransferState.INTERRUPTED ||
            it.state == TransferState.PENDING
    }
}
