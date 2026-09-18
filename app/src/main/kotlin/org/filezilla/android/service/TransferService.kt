package org.filezilla.android.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.filezilla.android.AppGraph
import org.filezilla.ftp.protocol.LogLevel

/**
 * Keeps the queue running when the app is not on screen.
 *
 * A transfer that stops the moment the user switches away is not a transfer
 * anyone can use for a large file, and Android will otherwise freeze the
 * process within minutes of it going to the background. So the queue runs in a
 * started foreground service, declared as `dataSync` -- the type that covers
 * "move the bytes the user asked for" -- and the work outlives every screen.
 *
 * The service owns the queue loop, not the UI: an activity that goes away
 * mid-transfer must not take the transfer with it.
 */
class TransferService : LifecycleService() {

    private lateinit var graph: AppGraph
    private lateinit var notifications: TransferNotifications
    private var queueJob: Job? = null

    private var lastNotifiedAt = 0L

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.of(this)
        notifications = TransferNotifications(this)
        notifications.ensureChannel()

        graph.networkGate.policy = graph.preferences.networkPolicy
        graph.networkGate.onAllowedChanged = { allowed ->
            if (allowed) {
                // The queue loop is parked in awaitAllowed and wakes itself;
                // if it already drained and stopped, start it again.
                graph.log.log(LogLevel.STATUS, "Network is usable again; resuming transfers")
                startQueue()
            } else {
                graph.log.log(LogLevel.STATUS, "Holding transfers until an allowed network is back")
                graph.transfers.onNetworkDisallowed()
            }
            repostIdleNotification()
        }
        graph.networkGate.start()

        // The notification is rebuilt as the transfer moves, so the user can
        // see it is alive without opening the app -- but not on every update.
        //
        // The engine reports progress every 64 KB, which on any real
        // connection is tens or hundreds of times a second. Posting a
        // notification that often does not make it smoother: Android rate
        // limits notification updates and silently drops the excess, so the
        // notification freezes at whatever it managed to show first while the
        // in-app progress carries on. That is exactly the bug this throttle
        // fixes -- the notification was stuck at 1% while the app showed 94%.
        lifecycleScope.launch {
            graph.transfers.active.collectLatest { progress ->
                if (queueJob?.isActive != true) return@collectLatest
                if (progress != null && !shouldRepost()) return@collectLatest
                notify(progress?.let { notifications.build(it, waitingCount(), heldForNetwork()) })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            graph.log.log(LogLevel.STATUS, "Transfers stopped by the user")
            graph.transfers.requestStop()
            queueJob?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        startQueue()

        // START_STICKY: if the process is killed under memory pressure, come
        // back and pick the queue up again. The journal holds the offsets, so
        // coming back costs nothing already transferred.
        return START_STICKY
    }

    private fun startQueue() {
        if (queueJob?.isActive == true) return
        queueJob = lifecycleScope.launch {
            try {
                graph.transfers.runQueue()
            } finally {
                // Whether the queue drained or the job was cancelled, there is
                // no longer a reason to hold the process in the foreground.
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = notifications.build(graph.transfers.active.value, 0, heldForNetwork())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                TransferNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(TransferNotifications.NOTIFICATION_ID, notification)
        }
    }

    /**
     * True when this update is worth spending a notification post on.
     *
     * A plain interval floor, which is all that is needed: the bytes and the
     * percentage both move continuously, so there is no update the user would
     * miss by waiting half a second for it.
     */
    private fun shouldRepost(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedAt < MIN_NOTIFY_INTERVAL_MILLIS) return false
        lastNotifiedAt = now
        return true
    }

    /** Transfers the queue still has to get to, for the notification's subtext. */
    private fun waitingCount(): Int =
        graph.transfers.waitingCount.value

    private fun notify(notification: android.app.Notification?) {
        if (notification == null) return
        val manager = getSystemService(android.app.NotificationManager::class.java)
        // Posting to the same id updates the foreground notification in place.
        runCatching { manager.notify(TransferNotifications.NOTIFICATION_ID, notification) }
    }

    /**
     * Refreshes the notification when nothing is transferring.
     *
     * Progress updates ride [notify]; this is for the moments when the reason
     * nothing is moving is the thing worth saying.
     */
    private fun repostIdleNotification() {
        notify(notifications.build(graph.transfers.active.value, waitingCount(), heldForNetwork()))
    }

    private fun heldForNetwork(): Boolean = !graph.networkGate.isAllowed

    override fun onDestroy() {
        graph.networkGate.onAllowedChanged = null
        graph.transfers.requestStop()
        queueJob?.cancel()
        graph.networkGate.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.filezilla.android.action.STOP"

        /**
         * Android tolerates roughly ten notification posts a second before it
         * starts dropping them; twice a second is smooth to read and leaves
         * the budget untouched.
         */
        private const val MIN_NOTIFY_INTERVAL_MILLIS = 500L

        /**
         * Starts the queue.
         *
         * Called from the UI, which is what makes starting a `dataSync`
         * foreground service legal on Android 12 and later: the app is visible,
         * and the user has just asked for the transfer.
         */
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TransferService::class.java).setAction(ACTION_STOP))
        }
    }
}
