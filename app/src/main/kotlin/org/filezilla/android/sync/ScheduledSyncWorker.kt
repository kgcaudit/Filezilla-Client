package org.filezilla.android.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import org.filezilla.android.AppGraph
import org.filezilla.android.R
import org.filezilla.android.data.SyncDirection
import org.filezilla.android.data.SyncJobEntity
import org.filezilla.android.data.SyncRunStatus
import org.filezilla.android.service.SyncNotifications
import org.filezilla.android.service.TransferService

/**
 * Runs one scheduled mirror when WorkManager says its time and conditions are
 * met.
 *
 * It does the light half itself -- scan, diff, make folders, queue the copies,
 * remove the extras -- and hands the heavy half, moving the bytes, to the same
 * [TransferService] every other transfer goes through, so a scheduled copy
 * resumes and survives exactly as a tapped one does. It runs as a foreground
 * worker for the scan so Android does not freeze it mid-listing and so it may
 * start that service from the background.
 *
 * A run never fails the work: a server that is down or a scan that throws is
 * recorded on the row as a failed run and left for the next period, rather than
 * retried in a tight loop that would wake the radio for a server that is not
 * coming back this minute.
 */
class ScheduledSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val notifications = SyncNotifications(context)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val name = inputData.getString(KEY_JOB_NAME).orEmpty()
        val notification = notifications.running(name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SyncNotifications.RUNNING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SyncNotifications.RUNNING_NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val graph = AppGraph.of(applicationContext)
        val job = graph.database.syncJobs().byIdBlocking(jobId) ?: return Result.success()
        // Disabled since it was scheduled: nothing to do. The scheduler cancels
        // disabled work, but a firing already in flight lands here too.
        if (!job.enabled) return Result.success()

        val site = graph.database.sites().byId(job.siteId)
        if (site == null) {
            record(graph, job, SyncRunStatus.FAILED, applicationContext.getString(R.string.sched_result_no_server))
            notifications.announce(job.name, applicationContext.getString(R.string.sched_result_no_server))
            return Result.success()
        }

        runCatching { setForeground(getForegroundInfo()) }

        val outcome = runCatching { ScheduledSync.run(graph, job, site) }.getOrElse {
            record(graph, job, SyncRunStatus.FAILED, applicationContext.getString(R.string.sched_result_failed))
            notifications.announce(job.name, applicationContext.getString(R.string.sched_result_failed))
            return Result.success()
        }

        if (outcome.hasQueuedWork) TransferService.start(applicationContext)

        val summary = summarise(job, outcome)
        record(graph, job, outcome.status, summary)
        notifications.announce(job.name, summary)
        return Result.success()
    }

    private suspend fun record(graph: AppGraph, job: SyncJobEntity, status: SyncRunStatus, summary: String) {
        graph.database.syncJobs().recordRun(
            id = job.id,
            at = System.currentTimeMillis(),
            status = status.name,
            result = summary,
        )
    }

    /** A short, localised line for the row and the notice. */
    private fun summarise(job: SyncJobEntity, outcome: ScheduledSyncResult): String {
        val ctx = applicationContext
        if (outcome.noop) return ctx.getString(R.string.sched_result_no_change)
        val parts = mutableListOf<String>()
        if (outcome.queued > 0) {
            parts += when (job.directionEnum) {
                SyncDirection.UPLOAD -> ctx.getString(R.string.sched_result_uploading, outcome.queued)
                SyncDirection.DOWNLOAD -> ctx.getString(R.string.sched_result_downloading, outcome.queued)
            }
        }
        if (outcome.deleted > 0) parts += ctx.getString(R.string.sched_result_deleted, outcome.deleted)
        if (outcome.failed > 0) parts += ctx.getString(R.string.sched_result_failed_count, outcome.failed)
        if (parts.isEmpty()) parts += ctx.getString(R.string.sched_result_done)
        return parts.joinToString(" · ")
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_JOB_NAME = "job_name"

        /** The unique-work name a job's periodic request is enqueued under. */
        fun workName(jobId: String): String = "scheduled-sync:$jobId"
    }
}
