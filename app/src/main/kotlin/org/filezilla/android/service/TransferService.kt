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

        // The policy comes with the gate; see AppGraph.
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
        // Distinct from the above: swapping Wi-Fi for mobile data leaves the
        // queue allowed the whole way through, so onAllowedChanged never
        // fires -- and the transfer sits on a socket bound to a network that
        // is gone.
        graph.networkGate.onNetworkChanged = { graph.transfers.onNetworkChanged() }
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

        // A foreground start is not always allowed: the system restarts the
        // service (START_STICKY) into the background after the process was
        // reclaimed, or the start was asked for while a permission dialog had
        // taken the app off-top. There is nothing to foreground then, and
        // pushing on would either crash here or be killed for not foregrounding
        // in time -- so give the service up. The queue is still in the journal
        // and resumes the next time the app is on screen (see MainActivity).
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startQueue()

        // START_STICKY: if the process is killed under memory pressure, come
        // back and pick the queue up again. The journal holds the offsets, so
        // coming back costs nothing already transferred.
        return START_STICKY
    }

    /**
     * The system's time limit for a background `dataSync` service is up.
     *
     * Android 15 caps a `dataSync` foreground service at a few hours within a
     * day; when that elapses it calls this, and then force-crashes the app if
     * the service is still in the foreground. So the queue is parked and the
     * service steps down: the transfer in flight is marked interrupted with its
     * offset kept, exactly as a user stop does, so it and the rest of the queue
     * resume the next time the app is on screen (see MainActivity's resume).
     * Only ever called on Android 15 and later; a no-op override elsewhere.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        graph.log.log(
            LogLevel.STATUS,
            "Transfers paused: the system's time limit for background transfers was reached",
        )
        graph.transfers.requestStop()
        queueJob?.cancel()
        stopSelf()
    }

    private fun startQueue() {
        if (queueJob?.isActive == true) return
        queueJob = lifecycleScope.launch {
            try {
                val outcome = graph.transfers.runQueue()
                // The second half of a move: each transfer took its own file
                // away as it landed, and what is left is the folders they
                // were in. Before the notice, so the ending is announced
                // once everything the move was going to do is done.
                graph.moveCleanup.sweep()
                // After the call and not in the finally: the finally also
                // runs when the user pressed stop, and announcing an ending
                // to somebody who has just cancelled is the app arguing with
                // them.
                QueueNotice.of(outcome)?.let { notifications.announce(it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // The user pressed stop, or a timeout cancelled us. Let it run
                // its course -- the finally still steps the service down.
                throw cancelled
            } catch (e: Exception) {
                // Each transfer already catches its own failure (runOne), so a
                // throw this far out is the queue machinery itself -- a journal
                // read, a move sweep, a stale destination at republish. One such
                // slip must not take the app down; the records keep their
                // offsets and the queue picks up again next time it is started.
                graph.log.log(
                    LogLevel.ERROR,
                    "The transfer queue stopped on an unexpected error: ${e.message}",
                )
            } finally {
                // Whether the queue drained or the job was cancelled, there is
                // no longer a reason to hold the process in the foreground.
                stopSelf()
            }
        }
    }

    /**
     * Enters the foreground, or reports that it could not.
     *
     * On Android 12 and later `startForeground` throws when the app is not in a
     * state allowed to start a foreground service -- the refusal is a
     * `ForegroundServiceStartNotAllowedException`, an [IllegalStateException].
     * That is not a bug to crash on: it is the system saying "not now", and the
     * one caller checks the result and gives the service up cleanly. Caught as
     * the superclass so the API 31 type is never named on an older device.
     */
    private fun startForegroundCompat(): Boolean = try {
        val notification = notifications.build(graph.transfers.foremostActive(), 0, heldForNetwork())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                TransferNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(TransferNotifications.NOTIFICATION_ID, notification)
        }
        true
    } catch (e: IllegalStateException) {
        graph.log.log(
            LogLevel.ERROR,
            "Could not bring the transfer service to the foreground: ${e.message}",
        )
        false
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

    /**
     * What the notification's subtext counts.
     *
     * Two transfers run at once but the notification shows one bar, so the
     * other one is counted here with the queue -- otherwise the user would see
     * a single file and no sign of the rest.
     */
    private fun waitingCount(): Int =
        graph.transfers.waitingCount.value + (graph.transfers.runningCount() - 1).coerceAtLeast(0)

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
        notify(notifications.build(graph.transfers.foremostActive(), waitingCount(), heldForNetwork()))
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
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                // Starting from a visible screen is allowed; this throws
                // (ForegroundServiceStartNotAllowedException, an
                // IllegalStateException) only when the app had just been taken
                // off-top -- a permission dialog the start raced. Swallowing it
                // keeps the tap from crashing the app: the transfer stays
                // queued in the journal and is handed to the service again when
                // the app returns to the foreground (see MainActivity's resume).
                android.util.Log.w("TransferService", "Deferred foreground service start", e)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TransferService::class.java).setAction(ACTION_STOP))
        }
    }
}
