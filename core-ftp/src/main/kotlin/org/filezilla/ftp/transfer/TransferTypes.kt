package org.filezilla.ftp.transfer

/** What the engine decided to do, and why. */
enum class TransferDisposition {
    /** Bytes were transferred. */
    TRANSFERRED,

    /** Bytes were transferred starting from a resume offset. */
    RESUMED,

    /**
     * Nothing was transferred because the sizes already matched.
     *
     * Port of the "End transfer since file sizes match" paths
     * (`filetransfer.cpp:102-114`, `201-215`): re-sending a file that is
     * already complete is worse than useless on a metered mobile connection.
     */
    ALREADY_COMPLETE,
}

/** Outcome of one file transfer. */
data class TransferOutcome(
    val disposition: TransferDisposition,
    /** Offset the transfer started at; 0 for a fresh transfer. */
    val resumeOffset: Long,
    /** Bytes moved in this attempt, excluding anything already present. */
    val bytesTransferred: Long,
    /** Total size of the file afterwards, when known. */
    val totalSize: Long?,
)

/** Progress callback; [transferred] counts bytes moved in this attempt. */
fun interface TransferProgressListener {
    fun onProgress(transferred: Long, resumeOffset: Long, totalSize: Long?)
}

/**
 * Raised when the server is known to mishandle resume offsets past 2 GB or
 * 4 GB and the transfer cannot safely continue.
 *
 * Port of the `FZ_REPLY_CRITICALERROR` path in `TestResumeCapability`
 * (`filetransfer.cpp:205-206`). This is deliberately fatal rather than a
 * silent restart: the local partial file is large, and quietly re-downloading
 * gigabytes is not something to do without telling the user.
 */
class ResumeUnsupportedException(val limitGigabytes: Int, message: String) : Exception(message)
