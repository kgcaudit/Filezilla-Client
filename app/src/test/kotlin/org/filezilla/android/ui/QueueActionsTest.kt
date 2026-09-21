package org.filezilla.android.ui

import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which bulk actions the transfer list offers, and when.
 *
 * The list could act on one transfer at a time and on nothing in bulk, so
 * thirty finished downloads had to be dismissed thirty times. The actions
 * are the easy part; the part worth pinning is which of them apply, because
 * a button offered when it would do nothing is the same defect as a button
 * missing when it would.
 */
class QueueActionsTest {

    private fun record(state: TransferState) = TransferRecord(
        id = state.name,
        direction = TransferDirection.DOWNLOAD,
        host = "h",
        port = 21,
        user = "u",
        remotePath = "/a",
        localPath = "/b",
        state = state,
    )

    private fun given(vararg states: TransferState) = queueActionsFor(states.map(::record))

    @Test
    fun `an empty list offers nothing`() {
        val actions = queueActionsFor(emptyList())

        assertFalse(actions.canPause)
        assertFalse(actions.canStart)
        assertFalse(actions.hasTidying)
    }

    @Test
    fun `something moving can be stopped`() {
        assertTrue(given(TransferState.RUNNING).canPause)
    }

    /**
     * The distinction the button turns on: thirty pending transfers with
     * the service stopped are not running, and offering to pause them would
     * be offering to stop something that is not going.
     */
    @Test
    fun `a queue that is merely outstanding is started, not paused`() {
        val actions = given(TransferState.PENDING, TransferState.PAUSED)

        assertFalse("offered to pause a queue that is not moving", actions.canPause)
        assertTrue(actions.canStart)
    }

    @Test
    fun `nothing is started while something is already moving`() {
        val actions = given(TransferState.RUNNING, TransferState.PENDING)

        assertTrue(actions.canPause)
        assertFalse(actions.canStart)
    }

    /**
     * A list of nothing but failures is exactly when a way to set them all
     * going again is wanted, so start has to reach them.
     */
    @Test
    fun `a list of failures can still be started`() {
        assertTrue(given(TransferState.FAILED).canStart)
    }

    /** A finished transfer has nothing left to do to it. */
    @Test
    fun `a list of finished transfers offers no start`() {
        val actions = given(TransferState.COMPLETED)

        assertFalse(actions.canStart)
        assertFalse(actions.canPause)
    }

    @Test
    fun `waiting for a network is outstanding, not moving`() {
        val actions = given(TransferState.WAITING_FOR_NETWORK)

        assertFalse(actions.canPause)
        assertTrue(actions.canStart)
    }

    // ------------------------------------------------------------- tidying

    @Test
    fun `finished rows can be cleared only when there are some`() {
        assertTrue(given(TransferState.COMPLETED).canClearFinished)
        assertFalse(given(TransferState.RUNNING).canClearFinished)
    }

    @Test
    fun `failed rows can be cleared only when there are some`() {
        assertTrue(given(TransferState.FAILED).canClearFailed)
        assertFalse(given(TransferState.COMPLETED).canClearFailed)
    }

    /** Emptying the list reaches everything, running included. */
    @Test
    fun `anything at all can be emptied`() {
        assertTrue(given(TransferState.RUNNING).canClearAll)
        assertTrue(given(TransferState.COMPLETED).canClearAll)
    }
}
