package org.filezilla.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.filezilla.android.R
import org.filezilla.android.transfer.ActiveProgress
import org.filezilla.android.ui.OpenAt
import org.filezilla.android.ui.Screen
import org.filezilla.android.ui.formatSize
import org.filezilla.android.transfer.secondsRemaining
import org.filezilla.android.ui.formatSpeed
import org.filezilla.android.ui.formatDuration

/** The notification the foreground service is required to show while it runs. */
class TransferNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_transfers),
            // LOW: a transfer runs for minutes and updates constantly. At
            // DEFAULT every update would be a chance to make a sound.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_transfers_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Says the queue has finished, and stays said.
     *
     * Its own id and its own channel, and both matter. The progress
     * notification belongs to the foreground service and is taken down with
     * it, so posting the ending there would show it for an instant and then
     * remove it. And the progress channel is deliberately silent -- a
     * transfer updates constantly and would chime every time -- while an
     * ending is the one moment worth a sound, which is a different channel's
     * job.
     */
    fun announce(notice: QueueNotice) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                DONE_CHANNEL_ID,
                context.getString(R.string.done_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.done_channel_description)
            },
        )

        val open = PendingIntent.getActivity(
            context,
            2,
            // The transfer list, because that is what the notice offers.
            OpenAt.intentTo(context, Screen.QUEUE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = context.getString(notice.title, *notice.args.toTypedArray())
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.done_tap))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        runCatching { manager.notify(DONE_NOTIFICATION_ID, notification) }
    }

    fun build(progress: ActiveProgress?, queued: Int, heldForNetwork: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            // The transfer list too: a notification about a running transfer
            // is a way into the queue, not a way into the app.
            OpenAt.intentTo(context, Screen.QUEUE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, TransferService::class.java).setAction(TransferService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                progress?.remotePath?.substringAfterLast('/')
                    ?: context.getString(
                        // "Preparing" while the queue is parked waiting for
                        // Wi-Fi would be a lie, and the user would be left
                        // wondering why nothing happens.
                        if (heldForNetwork) R.string.notification_waiting_network else R.string.notification_preparing,
                    ),
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, context.getString(R.string.action_stop), stop)

        if (heldForNetwork && progress == null) {
            builder.setProgress(0, 0, false)
            builder.setContentText(context.getString(R.string.notification_waiting_network_detail))
        }

        if (progress != null) {
            val total = progress.totalBytes
            val head = if (total != null && total > 0) {
                val percent = ((progress.bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100)
                builder.setProgress(100, percent, false)
                context.getString(
                    R.string.notification_progress,
                    formatSize(progress.bytes),
                    formatSize(total),
                    percent,
                )
            } else {
                // No size from the server: an indeterminate bar is honest,
                // where a made-up percentage would not be. The speed is still
                // knowable, so it is still shown.
                builder.setProgress(0, 0, true)
                context.getString(R.string.notification_progress_unknown, formatSize(progress.bytes))
            }

            // On the content line, not in the subtext. The subtext shares a
            // row with the app name and the clock, where the speed was easy to
            // miss -- and it was lost outright whenever the queue count, which
            // used the same slot, had something to say.
            //
            // Ordered so that truncation eats the least important part first:
            // how much, then how fast, then how long is left.
            builder.setContentText(listOfNotNull(head, speedText(progress)).joinToString(" · "))
        }

        // Its own slot now, so it no longer competes with the speed.
        if (queued > 0) {
            builder.setSubText(context.resources.getQuantityString(R.plurals.notification_queued, queued, queued))
        }

        return builder.build()
    }

    private fun speedText(progress: ActiveProgress): String? {
        val speed = progress.bytesPerSecond?.takeIf { it > 0 } ?: return null
        val left = secondsRemaining(progress.bytes, progress.totalBytes, speed)
            ?: return formatSpeed(speed)
        return context.getString(
            R.string.queue_speed_remaining,
            formatSpeed(speed),
            formatDuration(left),
        )
    }

    companion object {
        const val CHANNEL_ID = "transfers"
        const val NOTIFICATION_ID = 1

        /** The ending, which outlives the service that was running. */
        private const val DONE_CHANNEL_ID = "transfers-done"
        private const val DONE_NOTIFICATION_ID = 2
    }
}

