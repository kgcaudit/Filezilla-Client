package org.filezilla.android.ui

import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState

/**
 * What the queue *says* a transfer is doing, which is calmer than what it is
 * doing.
 *
 * A transfer whose connection dies does not sit still. The pass fails and the
 * record goes INTERRUPTED; the queue picks it straight back up and it goes
 * RUNNING; the connection fails at once and it goes INTERRUPTED again; the
 * network callback fires and everything goes WAITING_FOR_NETWORK; the
 * callback flaps and they go PENDING. Every one of those is true for the
 * moment it lasts, and showing each of them turned the card into a slideshow
 * of four different words and three different colours a second.
 *
 * So the screen is given fewer words than the queue has states. Nothing here
 * is a lie -- a transfer bouncing between RUNNING and INTERRUPTED is
 * reconnecting, and that is what it says.
 */
enum class TransferMood {
    /** Queued, never attempted. */
    QUEUED,

    /** Bytes are arriving. */
    RUNNING,

    /** Trying to get the connection back, however the queue is spelling that. */
    RECONNECTING,

    /** Held for a network the user allows; it starts again on its own. */
    WAITING_FOR_NETWORK,

    /** Stopped by the user. */
    PAUSED,

    /** Stopped for good, until the user restarts it. */
    FAILED,

    DONE,
}

/**
 * The mood of one transfer.
 *
 * [stalled] is the screen's own observation -- a running transfer that has
 * had no bytes for a while -- and it is what catches the half of a dropped
 * connection that the queue has not noticed yet.
 */
fun moodOf(record: TransferRecord, stalled: Boolean): TransferMood = when (record.state) {
    TransferState.COMPLETED -> TransferMood.DONE
    TransferState.FAILED -> TransferMood.FAILED
    TransferState.PAUSED -> TransferMood.PAUSED
    TransferState.WAITING_FOR_NETWORK -> TransferMood.WAITING_FOR_NETWORK
    TransferState.INTERRUPTED -> TransferMood.RECONNECTING
    TransferState.RUNNING -> if (stalled) TransferMood.RECONNECTING else TransferMood.RUNNING
    // A queued transfer that has already been attempted is the retry loop
    // coming round again, not a fresh arrival. Told apart by the attempt
    // count, because the state alone says the same thing for both.
    TransferState.PENDING ->
        if (record.attempts > 0) TransferMood.RECONNECTING else TransferMood.QUEUED
}

/** Whether a transfer in this mood should show a moving bar rather than a figure. */
val TransferMood.isUnsettled: Boolean
    get() = this == TransferMood.RECONNECTING || this == TransferMood.WAITING_FOR_NETWORK
