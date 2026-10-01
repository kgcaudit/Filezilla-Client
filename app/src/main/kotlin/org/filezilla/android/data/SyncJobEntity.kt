package org.filezilla.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Which way a scheduled mirror runs.
 *
 * Stored as a name so a value added later does not renumber existing rows,
 * exactly as [SiteProtocol] is. UPLOAD is the phone backing itself up onto a
 * server; DOWNLOAD is the phone pulling a server folder down.
 */
enum class SyncDirection { UPLOAD, DOWNLOAD }

/** How a scheduled mirror's last run turned out, for the line the list shows. */
enum class SyncRunStatus {
    /** Never run yet. */
    NONE,

    /** Ran and did what it set out to. */
    OK,

    /** Ran but some items failed. */
    PARTIAL,

    /** Could not run -- the server was unreachable, or it was called off. */
    FAILED,
}

/**
 * A standing instruction to mirror one folder onto another on a schedule.
 *
 * The counterpart of [org.filezilla.android.ui.SyncState] for the one-shot
 * mirror, but durable: a one-shot sync is captured from the two panes and run
 * once, while this is written down so it can be run again, by a background
 * worker, long after the panes that defined it have gone. The two ends are held
 * whole -- a local folder and a server folder under a saved [SiteEntity] -- so a
 * run needs nothing but the row and the site it names.
 *
 * Why only local-to-server and server-to-local, never server-to-server: the
 * same limit the one-shot mirror has, and for the same reason -- a scheduled run
 * has no second browse connection to spare, and the engine refuses it anyway.
 */
@Entity(tableName = "sync_jobs")
data class SyncJobEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "local_root") val localRoot: String,
    @ColumnInfo(name = "local_label") val localLabel: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "remote_root") val remoteRoot: String,
    @ColumnInfo(name = "remote_label") val remoteLabel: String,
    /** [SyncDirection] by name; UPLOAD is device -> server. */
    val direction: String,
    /**
     * Whether a run removes items the target has that the source does not.
     * Off by default and set deliberately, the destructive half of a mirror --
     * the same default the one-shot preview's switch starts at.
     */
    @ColumnInfo(name = "delete_extras") val deleteExtras: Boolean = false,
    /** How often the worker is asked to run, in minutes. WorkManager's floor is 15. */
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Long,
    /** Run only on an unmetered network (Wi-Fi). On by default: a mirror can be large. */
    @ColumnInfo(name = "requires_wifi") val requiresWifi: Boolean = true,
    /** Run only while charging. Off by default. */
    @ColumnInfo(name = "requires_charging") val requiresCharging: Boolean = false,
    /** Whether the schedule is live. A disabled job keeps its row but never fires. */
    val enabled: Boolean = true,
    /** When the last run finished, epoch millis; 0 for never. */
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long = 0,
    /** [SyncRunStatus] by name. */
    @ColumnInfo(name = "last_status") val lastStatus: String = SyncRunStatus.NONE.name,
    /** A short human summary of the last run, or null before the first. */
    @ColumnInfo(name = "last_result") val lastResult: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    val directionEnum: SyncDirection
        get() = runCatching { enumValueOf<SyncDirection>(direction) }.getOrDefault(SyncDirection.UPLOAD)

    val lastStatusEnum: SyncRunStatus
        get() = runCatching { enumValueOf<SyncRunStatus>(lastStatus) }.getOrDefault(SyncRunStatus.NONE)
}
