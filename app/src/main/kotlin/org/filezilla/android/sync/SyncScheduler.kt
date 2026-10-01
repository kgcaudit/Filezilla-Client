package org.filezilla.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.filezilla.android.data.SyncJobEntity
import java.util.concurrent.TimeUnit

/**
 * Turns a [SyncJobEntity] into (or out of) a standing WorkManager schedule.
 *
 * One periodic work request per job, keyed by the job's id, so editing a job
 * replaces its schedule rather than stacking a second one, and disabling or
 * deleting it cancels the work outright. WorkManager owns the waking, the
 * network and charging conditions, and the battery-safe batching -- which is the
 * whole reason the schedule is expressed as "every so often, when conditions
 * hold" rather than a wall-clock alarm.
 */
object SyncScheduler {

    /** WorkManager will not run a periodic job more often than this. */
    private const val MIN_INTERVAL_MINUTES = 15L

    /** Brings [job]'s schedule in line with its row: enqueues it, or cancels it when off. */
    fun apply(context: Context, job: SyncJobEntity) {
        val work = WorkManager.getInstance(context)
        if (!job.enabled) {
            work.cancelUniqueWork(ScheduledSyncWorker.workName(job.id))
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (job.requiresWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(job.requiresCharging)
            .build()
        val request = PeriodicWorkRequestBuilder<ScheduledSyncWorker>(
            job.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES),
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setInputData(inputFor(job))
            .build()
        // UPDATE so an edit keeps the same work and just changes its terms,
        // rather than KEEP (which would ignore the edit) or REPLACE (which would
        // reset the period and lose the next run already counted down).
        work.enqueueUniquePeriodicWork(
            ScheduledSyncWorker.workName(job.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Drops [jobId]'s schedule entirely, for a deleted job. */
    fun cancel(context: Context, jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(ScheduledSyncWorker.workName(jobId))
    }

    /**
     * Runs [job] once, now, outside its schedule and its conditions.
     *
     * For the row's "지금 실행": the user asked, so it does not wait for Wi-Fi or
     * charging. KEEP so a second tap while one is already running does nothing
     * rather than piling a duplicate on.
     */
    fun runNow(context: Context, job: SyncJobEntity) {
        val request = OneTimeWorkRequestBuilder<ScheduledSyncWorker>()
            .setInputData(inputFor(job))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ScheduledSyncWorker.workName(job.id) + ":now",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun inputFor(job: SyncJobEntity) = workDataOf(
        ScheduledSyncWorker.KEY_JOB_ID to job.id,
        ScheduledSyncWorker.KEY_JOB_NAME to job.name,
    )
}
