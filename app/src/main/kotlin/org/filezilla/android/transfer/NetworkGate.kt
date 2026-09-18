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
    private var online: Boolean = false

    @Volatile
    private var stopped: Boolean = false

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

    val isOnline: Boolean get() = online

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
        if (online) return

        val deadline = System.currentTimeMillis() + offlineCapMillis
        synchronized(lock) {
            while (!online && !stopped) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return
                // A bounded wait rather than an unbounded one: a missed
                // callback would otherwise leave this parked for good.
                lock.wait(minOf(remaining, POLL_INTERVAL_MILLIS))
            }
        }
    }

    private fun update() {
        val nowOnline = runCatching {
            val active = manager.activeNetwork ?: return@runCatching false
            val caps = manager.getNetworkCapabilities(active) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)

        synchronized(lock) {
            online = nowOnline
            if (nowOnline) lock.notifyAll()
        }
    }

    private companion object {
        const val DEFAULT_OFFLINE_CAP_MILLIS = 10 * 60 * 1000L
        const val POLL_INTERVAL_MILLIS = 5_000L
    }
}
