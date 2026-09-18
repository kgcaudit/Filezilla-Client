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
import org.filezilla.android.ui.MainActivity

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

    fun build(progress: ActiveProgress?, queued: Int): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
                    ?: context.getString(R.string.notification_preparing),
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, context.getString(R.string.action_stop), stop)

        if (progress != null) {
            val total = progress.totalBytes
            if (total != null && total > 0) {
                val percent = ((progress.bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100)
                builder.setProgress(100, percent, false)
                builder.setContentText(
                    context.getString(
                        R.string.notification_progress,
                        formatBytes(progress.bytes),
                        formatBytes(total),
                        percent,
                    ),
                )
            } else {
                // No size from the server: an indeterminate bar is honest,
                // where a made-up percentage would not be.
                builder.setProgress(0, 0, true)
                builder.setContentText(
                    context.getString(R.string.notification_progress_unknown, formatBytes(progress.bytes)),
                )
            }
        }

        if (queued > 0) {
            builder.setSubText(context.resources.getQuantityString(R.plurals.notification_queued, queued, queued))
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "transfers"
        const val NOTIFICATION_ID = 1
    }
}

/** Bytes as the queue screen and the notification both show them. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}
