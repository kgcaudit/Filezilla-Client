package org.filezilla.android.transfer

/**
 * Thrown inside a running transfer's progress callback to stop it where it
 * stands.
 *
 * Deliberately **not** an [java.io.IOException].
 * [org.filezilla.ftp.transfer.RetryPolicy] retries those, because a broken
 * socket is exactly what resume exists for -- but a transfer the user paused
 * must not reconnect and carry on. Getting this wrong would make the pause
 * button look like it did nothing.
 */
class TransferPausedException(val transferId: String) :
    RuntimeException("transfer $transferId was paused")

/**
 * Carries a pause request from whichever thread the UI is on to the thread
 * running the transfer.
 *
 * Writing `PAUSED` to the journal is not enough on its own, and that is the
 * bug this exists to fix:
 * [org.filezilla.ftp.journal.JournalledTransfer] keeps its own copy of the
 * record marked `RUNNING` and writes it back every megabyte, so a `PAUSED`
 * written from outside is erased within a megabyte -- and nothing stops the
 * transfer anyway.
 *
 * So the pause is delivered where the transfer can actually notice it: the
 * progress callback, which the engine calls every 64 KB and does not wrap in
 * a `catch`. Throwing from there unwinds through the engine's `finally` and
 * `use` blocks, which close the data connection, close the control connection
 * and truncate the partial file to the bytes actually received -- the same
 * cleanup a dropped connection gets. The journalled offset survives, so
 * resuming costs nothing already transferred.
 */
class PauseSignal {

    @Volatile
    private var requested: String? = null

    /** Asks the transfer with [id] to stop at its next progress callback. */
    fun request(id: String) {
        requested = id
    }

    fun isRequested(id: String): Boolean = requested == id

    /**
     * Cleared when a transfer's run ends, so a request that arrived too late
     * to stop anything cannot stop the *next* run of the same transfer.
     */
    fun clear() {
        requested = null
    }

    /** Called from the progress callback; throws when this transfer is paused. */
    fun stopIfRequested(id: String) {
        if (requested == id) throw TransferPausedException(id)
    }
}
