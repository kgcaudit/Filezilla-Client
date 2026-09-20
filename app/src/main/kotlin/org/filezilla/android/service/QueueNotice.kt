package org.filezilla.android.service

import androidx.annotation.StringRes
import org.filezilla.android.R
import org.filezilla.android.transfer.TransferManager.QueueOutcome

/**
 * What to say when the queue has finished.
 *
 * The queue used to say nothing. Its notification is the foreground
 * service's, so it goes away with the service the moment the last file
 * lands -- which from the user's side is indistinguishable from the app
 * having given up. A long copy started and then left alone had no ending at
 * all; the only way to find out was to open the app and look.
 *
 * Kept apart from the notification so the wording is a decision that can be
 * read and tested rather than a branch buried in a builder. Failures are
 * never folded into a count of successes: "38 finished" when six of them did
 * not is the kind of reassurance that costs trust exactly once.
 */
data class QueueNotice(@StringRes val title: Int, val args: List<Int>) {

    companion object {
        /** Null when there is nothing worth interrupting anybody for. */
        fun of(outcome: QueueOutcome): QueueNotice? = when {
            outcome.isEmpty -> null

            outcome.failed == 0 && outcome.completed == 1 ->
                QueueNotice(R.string.done_all_one, emptyList())

            outcome.failed == 0 ->
                QueueNotice(R.string.done_all, listOf(outcome.completed))

            outcome.completed == 0 && outcome.failed == 1 ->
                QueueNotice(R.string.done_all_failed_one, emptyList())

            outcome.completed == 0 ->
                QueueNotice(R.string.done_all_failed, listOf(outcome.failed))

            else ->
                QueueNotice(R.string.done_some_failed, listOf(outcome.completed, outcome.failed))
        }
    }
}
