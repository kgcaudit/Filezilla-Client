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
class TransferPausedException(val transferId: String, val reason: StopReason) :
    RuntimeException("transfer $transferId was stopped: $reason")

/**
 * Why a transfer was stopped where it stood.
 *
 * The queue has to tell these apart after the fact. A transfer held back for
 * the network starts again by itself when the network comes back; one the user
 * paused must not, or the pause button would undo itself the next time the
 * phone changed network.
 */
enum class StopReason {
    /** The user pressed pause. */
    USER,

    /** The only connection left is one the user ruled out. */
    NETWORK,

    /**
     * The user cancelled it. The record and its bytes go, rather than being
     * kept for a resume that will never be asked for.
     */
    CANCEL,
}

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

    /**
     * Keyed by transfer, because two of them run at once. A single slot would
     * mean pausing one transfer stopped whichever happened to check first.
     */
    private val requested = java.util.concurrent.ConcurrentHashMap<String, StopReason>()

    /**
     * Asks the transfer with [id] to stop at its next progress callback.
     *
     * A cancel already asked for is never replaced. The network dropping a
     * moment after the user pressed cancel would otherwise turn their cancel
     * into a wait, and the transfer they dismissed would come back when Wi-Fi
     * did.
     */
    fun request(id: String, reason: StopReason = StopReason.USER) {
        if (reason == StopReason.CANCEL) {
            requested[id] = reason
            return
        }
        requested.putIfAbsent(id, reason)
    }

    fun isRequested(id: String): Boolean = requested.containsKey(id)

    /** Why [id] was asked to stop, or null if it was not. */
    fun reasonFor(id: String): StopReason? = requested[id]

    /**
     * Cleared when a transfer's run ends, so a request that arrived too late
     * to stop anything cannot stop the *next* run of the same transfer.
     */
    fun clear(id: String) {
        requested.remove(id)
    }

    /** Called from the progress callback; throws when this transfer is paused. */
    fun stopIfRequested(id: String) {
        val reason = requested[id] ?: return
        throw TransferPausedException(id, reason)
    }
}
