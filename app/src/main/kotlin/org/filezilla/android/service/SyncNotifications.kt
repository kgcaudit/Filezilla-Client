package org.filezilla.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import org.filezilla.android.R
import org.filezilla.android.ui.OpenAt
import org.filezilla.android.ui.Screen

/**
 * The two notifications a scheduled mirror posts: the quiet one it wears while
 * it runs in the background, and the one that stays to say how it went.
 *
 * Apart from the transfer notifications on purpose. A scheduled run fires when
 * nobody is looking, so its "it happened" notice is the only trace of it, and it
 * belongs on its own channel the user can mute without silencing the transfer
 * progress they asked to watch.
 */
class SyncNotifications(private val context: android.content.Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RUNNING_CHANNEL_ID,
                context.getString(R.string.sched_channel_running),
                // LOW: a background mirror running is a reassurance, not an alert.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.sched_channel_running_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                context.getString(R.string.sched_channel_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.sched_channel_result_description)
            },
        )
    }

    /** The foreground notification worn while [jobName] scans and queues. */
    fun running(jobName: String): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, RUNNING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.sched_running_title))
            .setContentText(jobName)
            .setOngoing(true)
            .setContentIntent(open())
            .build()
    }

    /** The lasting notice of how [jobName]'s run turned out. */
    fun announce(jobName: String, summary: String) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(jobName)
            .setContentText(summary)
            .setContentIntent(open())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(RESULT_NOTIFICATION_ID, notification)
        }
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        context,
        3,
        OpenAt.intentTo(context, Screen.SCHEDULED_SYNC),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val RUNNING_CHANNEL_ID = "scheduled-sync"
        const val RESULT_CHANNEL_ID = "scheduled-sync-result"
        const val RUNNING_NOTIFICATION_ID = 7301
        const val RESULT_NOTIFICATION_ID = 7302
    }
}
