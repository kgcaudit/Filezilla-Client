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

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.of(this)
        notifications = TransferNotifications(this)
        notifications.ensureChannel()
        graph.networkGate.start()

        // The notification is rebuilt as the transfer moves, so the user can
        // see it is alive without opening the app.
        lifecycleScope.launch {
            graph.transfers.active.collectLatest { progress ->
                if (queueJob?.isActive == true) {
                    notify(progress?.let { notifications.build(it, 0) })
                }
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
        val notification = notifications.build(graph.transfers.active.value, 0)
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

    private fun notify(notification: android.app.Notification?) {
        if (notification == null) return
        val manager = getSystemService(android.app.NotificationManager::class.java)
        // Posting to the same id updates the foreground notification in place.
        runCatching { manager.notify(TransferNotifications.NOTIFICATION_ID, notification) }
    }

    override fun onDestroy() {
        graph.transfers.requestStop()
        queueJob?.cancel()
        graph.networkGate.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.filezilla.android.action.STOP"

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
