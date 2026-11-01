package org.filezilla.android.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.filezilla.android.R

/**
 * Keeps the process alive and the CPU awake while a local copy or move runs,
 * the way [TransferService] does for the network queue.
 *
 * A copy between two folders on the device is the user's bytes moving just as
 * much as a download is, but it ran only in the view model's own scope with
 * nothing holding the process up. With the screen off and the app in the
 * background Android freezes a process that has no foreground service -- the
 * cached-app freezer on Android 12 and later does it within seconds -- and a
 * deep doze suspends the CPU on top of that, so the copy stopped mid-file and
 * did not move again until the screen came back on.
 *
 * So the paste raises this for its duration: a foreground service of the same
 * `dataSync` type a transfer uses, plus a partial wake lock so the bytes keep
 * moving through a screen-off doze. It holds nothing but the process up -- the
 * copy itself still runs in the view model -- and it is taken down the moment
 * the copy ends, whichever way it ends.
 */
class LocalWorkService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TransferNotifications(this).ensureChannel()
        // What the work is, in the user's words: "파일 복사 중" for a paste, or
        // the archive operation's own label ("압축 해제 중", "압축 중" ...). The
        // app shows the real progress; this only names what is alive.
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: getString(R.string.notification_copying)
        val foregrounded = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(label),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification(label))
            }
            true
        }.getOrDefault(false)

        if (!foregrounded) {
            // The system refused the foreground start (the app was taken
            // off-top in the window between the tap and here). Nothing to hold
            // up then; the copy still runs, just unprotected in that rare case.
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        // A local copy is not journaled and cannot resume, so there is nothing
        // to come back to if the process is reclaimed: do not be restarted.
        return START_NOT_STICKY
    }

    private fun notification(label: String): Notification =
        NotificationCompat.Builder(this, TransferNotifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Indeterminate: the view model owns the real progress and shows it
            // in the app. This bar only says the work is alive.
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // A safety cap, not the real bound: the service releases it when the
            // copy ends, and a killed process drops it with the process. The
            // timeout only covers a crash that skips onDestroy, and is far
            // longer than any same-device copy takes.
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 3
        private const val WAKE_LOCK_TAG = "olo-explorer:local-copy"
        private const val EXTRA_LABEL = "label"

        /** One hour: a safety net for a crash, well past any same-device copy. */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 60L * 60L * 1000L

        /**
         * Raise the keep-alive for a local operation, named by [label] in its
         * notification (null falls back to "copying"). Legal because the caller
         * is the app acting on a tap, so it is on screen; the catch covers the
         * one window where it was just taken off-top, exactly as [TransferService].
         */
        fun start(context: Context, label: String? = null) {
            val intent = Intent(context, LocalWorkService::class.java)
            if (label != null) intent.putExtra(EXTRA_LABEL, label)
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                android.util.Log.w("LocalWorkService", "Deferred local-work service start", e)
            }
        }

        /** Take it down. [Context.stopService] is allowed from the background,
         *  so the copy can end it even with the screen off. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LocalWorkService::class.java)) }
        }
    }
}
