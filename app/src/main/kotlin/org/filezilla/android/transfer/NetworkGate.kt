package org.filezilla.android.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Knows whether the phone has a usable network, and lets a waiting transfer
 * find out the moment it gets one.
 *
 * This is the app's half of network-change recovery. The engine's half --
 * reconnecting and resuming from the right offset -- is already done in
 * [org.filezilla.ftp.transfer.ResilientTransfer]; what it cannot know is that
 * the phone is in a lift with no signal at all, and that spending a retry
 * attempt on that is throwing one away. So the backoff the retry policy asked
 * for is served in full, and then, only if there is still no network, the wait
 * continues until one appears.
 *
 * It is additive on purpose: it never shortens the engine's backoff, because
 * how soon it is reasonable to hit a server again is the engine's decision and
 * not this class's.
 */
class NetworkGate(context: Context) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Object()

    @Volatile
    private var allowed: Boolean = false

    @Volatile
    private var stopped: Boolean = false

    /**
     * What counts as a usable connection. Read on every check rather than
     * captured, so turning Wi-Fi-only on stops a running transfer instead of
     * taking effect on the next app start.
     */
    @Volatile
    var policy: NetworkPolicy = NetworkPolicy.ANY
        set(value) {
            field = value
            update()
        }

    /**
     * Told whenever the answer to "may the queue transfer right now" changes.
     *
     * The queue needs both edges: losing an allowed network has to stop what
     * is running, and getting one back has to start it again.
     */
    @Volatile
    var onAllowedChanged: ((Boolean) -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update()
        override fun onLost(network: Network) = update()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
    }

    fun start() {
        synchronized(lock) { stopped = false }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
        update()
    }

    fun stop() {
        runCatching { manager.unregisterNetworkCallback(callback) }
        synchronized(lock) {
            stopped = true
            lock.notifyAll()
        }
    }

    /** True when the current connection satisfies [policy]. */
    val isAllowed: Boolean get() = allowed

    /**
     * Asks the platform right now, rather than reporting what the callback
     * last saw.
     *
     * The UI needs this: [start] is called by the transfer service, so a
     * screen that has never started one would otherwise be told the phone is
     * offline and would blame the wrong thing for a failed connection.
     */
    fun currentStatus(): NetworkStatus = runCatching {
        val active = manager.activeNetwork ?: return@runCatching NetworkStatus.OFFLINE
        val caps = manager.getNetworkCapabilities(active) ?: return@runCatching NetworkStatus.OFFLINE
        NetworkStatus(
            online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }.getOrDefault(NetworkStatus.OFFLINE)

    fun currentlyOnline(): Boolean = currentStatus().online

    /** Whether the queue may transfer right now, asked of the platform. */
    fun currentlyAllowed(): Boolean = policy.allows(currentStatus())

    /**
     * The `sleep` an [org.filezilla.ftp.transfer.ResilientTransfer] is given.
     *
     * Serves [backoffMillis] in full, then keeps waiting while the phone is
     * offline, up to [offlineCapMillis]. The cap is what keeps a transfer from
     * blocking forever on a phone that is simply out of range: after it, the
     * attempt goes ahead, fails, and the retry policy gets to decide whether
     * the transfer is finished.
     */
    @Throws(InterruptedException::class)
    fun waitBeforeRetry(backoffMillis: Long, offlineCapMillis: Long = DEFAULT_OFFLINE_CAP_MILLIS) {
        if (backoffMillis > 0) Thread.sleep(backoffMillis)
        if (allowed) return

        val deadline = System.currentTimeMillis() + offlineCapMillis
        synchronized(lock) {
            while (!allowed && !stopped) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return
                // A bounded wait rather than an unbounded one: a missed
                // callback would otherwise leave this parked for good.
                lock.wait(minOf(remaining, POLL_INTERVAL_MILLIS))
            }
        }
    }

    /**
     * Blocks until the connection satisfies the policy, or the gate stops.
     *
     * Unbounded on purpose, unlike [waitBeforeRetry]: a transfer held back for
     * the network is not failing and has no retry budget to spend, so waiting
     * out an hour on cellular is the correct thing to do rather than giving up
     * and spending the user's data.
     */
    @Throws(InterruptedException::class)
    fun awaitAllowed() {
        synchronized(lock) {
            while (!allowed && !stopped) {
                lock.wait(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun update() {
        val nowAllowed = currentlyAllowed()
        val changed: Boolean
        synchronized(lock) {
            changed = nowAllowed != allowed
            allowed = nowAllowed
            if (nowAllowed) lock.notifyAll()
        }
        // Outside the lock: the listener stops a running transfer, and doing
        // that while holding the lock a waiting one needs would deadlock.
        if (changed) onAllowedChanged?.invoke(nowAllowed)
    }

    private companion object {
        const val DEFAULT_OFFLINE_CAP_MILLIS = 10 * 60 * 1000L
        const val POLL_INTERVAL_MILLIS = 5_000L
    }
}
