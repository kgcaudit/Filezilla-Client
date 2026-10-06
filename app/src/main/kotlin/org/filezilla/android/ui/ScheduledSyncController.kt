package org.filezilla.android.ui

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.filezilla.android.data.SyncDirection
import org.filezilla.android.data.SyncJobDao
import org.filezilla.android.data.SyncJobEntity
import org.filezilla.android.sync.SyncScheduler
import java.util.UUID

/**
 * The standing scheduled folder-mirrors: the list of them, and making,
 * editing, enabling, deleting and running one now.
 *
 * Lifted out of [MainViewModel], which had grown to hold every screen's logic
 * at once. This is one coherent job -- the saved jobs and their WorkManager
 * schedule -- and nothing here touches the live panes: a new job's two ends
 * are captured into a [SyncJobDraft] by the view model (which can read the
 * panes) and handed in, so this side depends only on the database and the
 * scheduler.
 *
 * [scope] is the view model's own, so the jobs flow and the writes live
 * exactly as long as the view model does.
 */
class ScheduledSyncController(
    private val app: Application,
    private val dao: SyncJobDao,
    private val scope: CoroutineScope,
) {
    /** The standing jobs, newest first; what the scheduled-sync screen lists. */
    val jobs: StateFlow<List<SyncJobEntity>> = dao.observeAll()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Writes a new scheduled job and starts its schedule. */
    fun add(
        name: String,
        draft: SyncJobDraft,
        direction: SyncDirection,
        intervalMinutes: Long,
        requiresWifi: Boolean,
        requiresCharging: Boolean,
        deleteExtras: Boolean,
    ) {
        val job = SyncJobEntity(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { draft.suggestedName },
            localRoot = draft.localRoot,
            localLabel = draft.localLabel,
            siteId = draft.siteId,
            remoteRoot = draft.remoteRoot,
            remoteLabel = draft.remoteLabel,
            direction = direction.name,
            deleteExtras = deleteExtras,
            intervalMinutes = intervalMinutes,
            requiresWifi = requiresWifi,
            requiresCharging = requiresCharging,
        )
        scope.launch {
            dao.upsert(job)
            SyncScheduler.apply(app, job)
        }
    }

    /** Saves an edited job and brings its schedule in line with the change. */
    fun update(job: SyncJobEntity) {
        scope.launch {
            dao.upsert(job)
            SyncScheduler.apply(app, job)
        }
    }

    /** Turns a job's schedule on or off without losing the job. */
    fun setEnabled(job: SyncJobEntity, on: Boolean) = update(job.copy(enabled = on))

    /** Forgets a job and cancels its schedule. */
    fun delete(job: SyncJobEntity) {
        scope.launch {
            dao.delete(job)
            SyncScheduler.cancel(app, job.id)
        }
    }

    /** Runs a job once now, outside its schedule and conditions. */
    fun runNow(job: SyncJobEntity) = SyncScheduler.runNow(app, job)
}
