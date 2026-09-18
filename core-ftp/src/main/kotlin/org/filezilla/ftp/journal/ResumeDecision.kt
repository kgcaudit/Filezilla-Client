package org.filezilla.ftp.journal

/** What to do with a partial transfer that is being picked up again. */
sealed interface ResumeDecision {

    /** Carry on from [offset]. */
    data class ResumeFrom(val offset: Long) : ResumeDecision

    /** The partial file cannot be trusted; fetch the whole thing again. */
    data class RestartFromZero(val reason: String) : ResumeDecision

    /** Everything is already there. */
    data object AlreadyComplete : ResumeDecision
}

/**
 * Decides whether a journalled offset is still safe to resume from.
 *
 * FileZilla does not need this: it resumes within one run, where nothing has
 * had a chance to change underneath it, and anything ambiguous goes to the user
 * through its overwrite prompt. An Android transfer is different -- it can be
 * picked up hours later, after the app was killed, against a file someone else
 * may have replaced -- and there is nobody watching to answer a prompt. So the
 * offset is checked against the server before it is used.
 *
 * The check is deliberately conservative. Restarting a download wastes data;
 * resuming against the wrong file produces a corrupt one that looks fine, and
 * the user may not find out until they open it.
 */
object ResumeSafety {

    /**
     * @param record what the journal remembers.
     * @param currentRemote what the server says now, from `SIZE`/`MDTM` or a
     *   listing.
     * @param localPartialSize the partial file's actual length, or null when
     *   it is gone.
     */
    fun decide(
        record: TransferRecord,
        currentRemote: RemoteFingerprint,
        localPartialSize: Long?,
    ): ResumeDecision {
        if (localPartialSize == null || localPartialSize == 0L) {
            return ResumeDecision.RestartFromZero("no partial file on disk")
        }

        // The journal is the authority on how many bytes are known good. A
        // longer file means bytes were written that were never accounted for,
        // most likely because the process died between the write and the
        // journal update, so only the accounted-for prefix is trusted.
        val trusted = minOf(record.bytesTransferred, localPartialSize)
        if (trusted <= 0) {
            return ResumeDecision.RestartFromZero("nothing recorded as transferred")
        }

        val remembered = record.fingerprint
        if (remembered != null && remembered.isUsable && currentRemote.isUsable) {
            if (remembered.size != null && currentRemote.size != null &&
                remembered.size != currentRemote.size
            ) {
                return ResumeDecision.RestartFromZero(
                    "remote size changed from ${remembered.size} to ${currentRemote.size}",
                )
            }
            if (remembered.modifiedMillis != null && currentRemote.modifiedMillis != null &&
                remembered.modifiedMillis != currentRemote.modifiedMillis
            ) {
                return ResumeDecision.RestartFromZero("remote file was modified since the transfer began")
            }
        }

        val remoteSize = currentRemote.size
        if (remoteSize != null) {
            if (trusted >= remoteSize) {
                // Either it is all there, or the partial file is somehow longer
                // than the whole remote file, which means it is not a prefix.
                return if (trusted == remoteSize) {
                    ResumeDecision.AlreadyComplete
                } else {
                    ResumeDecision.RestartFromZero(
                        "local partial ($trusted bytes) is longer than the remote file ($remoteSize bytes)",
                    )
                }
            }
        }

        return ResumeDecision.ResumeFrom(trusted)
    }
}
