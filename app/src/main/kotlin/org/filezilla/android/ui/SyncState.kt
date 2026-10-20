package org.filezilla.android.ui

import org.filezilla.android.data.SiteEntity
import org.filezilla.android.files.Meta
import org.filezilla.android.files.SyncPlan

/**
 * One end of a mirror: a folder on the phone or on a server, and what to call
 * it on screen.
 *
 * A sealed type rather than the pane it came from, because by the time the
 * mirror is running the pane may have moved on -- the source and target are
 * fixed the moment the user asks, so they are captured here whole.
 */
sealed interface SyncEndpoint {
    /** The folder this end mirrors from or to, as its side names paths. */
    val root: String

    /** What to show the user: a device or server name and the folder. */
    val label: String

    data class LocalDir(override val root: String, override val label: String) : SyncEndpoint

    data class RemoteDir(
        override val root: String,
        override val label: String,
        val site: SiteEntity,
    ) : SyncEndpoint
}

/**
 * A mirror worked out and waiting for the user to say go.
 *
 * Everything the preview shows and the run needs, captured so neither depends
 * on the panes staying where they were: which way it goes, what it will do
 * (the [plan]), whether deletion was asked for, and what the scan had to leave
 * out.
 */
data class SyncState(
    val source: SyncEndpoint,
    val target: SyncEndpoint,
    val plan: SyncPlan,
    val deleteExtras: Boolean,
    /** True when a scan hit a cap, so the plan is not the whole tree. */
    val truncated: Boolean = false,
    /** Links passed over on either side, so the user is told, not surprised. */
    val skippedLinks: Int = 0,
    /** True once the user has said go and the mirror is being carried out. */
    val running: Boolean = false,
    /**
     * The two scanned listings, kept so the preview can switch between a one-way
     * mirror and a two-way sync without scanning again -- the diff is pure and
     * cheap, so the toggle just re-runs it.
     */
    val sourceTree: Map<String, Meta> = emptyMap(),
    val targetTree: Map<String, Meta> = emptyMap(),
    /**
     * True for a two-way sync: each side gains what the other has and, for a
     * file on both, the newer wins. Nothing is deleted (without a record of what
     * was there before, a delete on one side cannot be told from a new file on
     * the other). [reversePlan] is then the target-to-source half.
     */
    val twoWay: Boolean = false,
    /** The second direction of a two-way sync (target -> source); null one-way. */
    val reversePlan: SyncPlan? = null,
)

/** How a finished mirror turned out, for the line shown when it is done. */
data class SyncOutcome(
    val made: Int = 0,
    val copied: Int = 0,
    val deleted: Int = 0,
    val failed: Int = 0,
    /** True when the user called the run off partway. */
    val cancelled: Boolean = false,
)
