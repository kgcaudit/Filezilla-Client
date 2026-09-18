package org.filezilla.android.transfer

import org.filezilla.ftp.journal.TransferState

/** What the queue insists on before it will spend the user's connection. */
enum class NetworkPolicy {
    /** Any working connection, metered or not. */
    ANY,

    /**
     * Only a connection that costs nothing to use.
     *
     * Judged by the platform's metered flag rather than by the Wi-Fi radio,
     * because what the user is protecting is their data allowance, not the
     * radio: a phone sharing its cellular data over Wi-Fi is Wi-Fi by
     * transport and is exactly what this setting exists to avoid. A Wi-Fi
     * network the user has marked metered is one they have told Android costs
     * them money, and it is taken at its word.
     */
    UNMETERED,
}

/** What the phone has right now, as far as the queue cares. */
data class NetworkStatus(val online: Boolean, val unmetered: Boolean) {
    companion object {
        val OFFLINE = NetworkStatus(online = false, unmetered = false)
    }
}

/**
 * Whether [status] is a connection this policy will transfer over.
 *
 * Top-level and tested: getting it wrong in the lenient direction spends the
 * user's cellular allowance on a 1.9 GB download they asked to keep on Wi-Fi,
 * which is not a bug they can undo.
 */
fun NetworkPolicy.allows(status: NetworkStatus): Boolean = when (this) {
    NetworkPolicy.ANY -> status.online
    NetworkPolicy.UNMETERED -> status.online && status.unmetered
}

/**
 * Whether the return of an allowed network should start this transfer again.
 *
 * Only what the network itself held back. A transfer the user paused must stay
 * paused: reviving it here would mean the pause button quietly undoes itself
 * the next time the phone changes network, which is worse than it never having
 * worked -- the user would have no reason to look.
 *
 * A FAILED one stays failed too. Whatever it hit was not the network, and the
 * user restarts it themselves, which is also what resets its retry budget.
 */
fun startsAgainWhenNetworkReturns(state: TransferState): Boolean =
    state == TransferState.WAITING_FOR_NETWORK

/**
 * Whether losing an allowed network should hold this transfer back.
 *
 * The three the queue would otherwise pick up. COMPLETED and FAILED are done
 * with, and PAUSED is the user's, so moving it into the network wait would
 * hand it back to the queue the moment Wi-Fi returned.
 */
fun waitsForNetwork(state: TransferState): Boolean = when (state) {
    TransferState.PENDING, TransferState.RUNNING, TransferState.INTERRUPTED -> true
    TransferState.PAUSED, TransferState.WAITING_FOR_NETWORK,
    TransferState.COMPLETED, TransferState.FAILED,
    -> false
}

/**
 * What the queue needs to know about the connection.
 *
 * Narrow on purpose. [NetworkGate] answers it from the platform; a test
 * answers it directly, which is what lets the app's resume behaviour be driven
 * against a real server without also simulating Android's connectivity stack.
 */
interface TransferGate {

    /** Whether the queue may transfer right now. */
    val isAllowed: Boolean

    /** Blocks until it may, or until the gate stops. */
    @Throws(InterruptedException::class)
    fun awaitAllowed()

    /** The `sleep` a retrying transfer is given; see [NetworkGate.waitBeforeRetry]. */
    @Throws(InterruptedException::class)
    fun waitBeforeRetry(backoffMillis: Long)
}
