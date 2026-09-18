package org.filezilla.android.transfer

import org.filezilla.ftp.journal.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    private val wifi = NetworkStatus(online = true, unmetered = true)
    private val cellular = NetworkStatus(online = true, unmetered = false)
    private val offline = NetworkStatus.OFFLINE

    @Test
    fun `any policy transfers over whatever is connected`() {
        assertTrue(NetworkPolicy.ANY.allows(wifi))
        assertTrue(NetworkPolicy.ANY.allows(cellular))
    }

    /**
     * The reason this is tested rather than assumed: being lenient here spends
     * the user's cellular allowance on a download they asked to keep on Wi-Fi,
     * and they cannot get those bytes back.
     */
    @Test
    fun `wifi-only refuses a metered connection`() {
        assertTrue(NetworkPolicy.UNMETERED.allows(wifi))
        assertFalse(NetworkPolicy.UNMETERED.allows(cellular))
    }

    /**
     * A phone sharing its cellular data over Wi-Fi is Wi-Fi by transport and
     * metered in fact. The metered flag is what the setting is protecting.
     */
    @Test
    fun `a metered hotspot counts as mobile data, not as wifi`() {
        val hotspot = NetworkStatus(online = true, unmetered = false)

        assertFalse(NetworkPolicy.UNMETERED.allows(hotspot))
    }

    @Test
    fun `no connection is never allowed, whatever the policy`() {
        assertFalse(NetworkPolicy.ANY.allows(offline))
        assertFalse(NetworkPolicy.UNMETERED.allows(offline))
    }

    /** Offline is offline even if the last known network was unmetered. */
    @Test
    fun `unmetered but offline is not allowed`() {
        val stale = NetworkStatus(online = false, unmetered = true)

        assertFalse(NetworkPolicy.ANY.allows(stale))
        assertFalse(NetworkPolicy.UNMETERED.allows(stale))
    }

    // ------------------------------------------- which transfers are held

    /**
     * The bug this guards is the one the user would notice and could not
     * explain: pressing pause, walking into Wi-Fi range, and finding the
     * transfer running again.
     */
    @Test
    fun `a transfer the user paused is not revived by wifi coming back`() {
        assertFalse(startsAgainWhenNetworkReturns(TransferState.PAUSED))
        assertTrue(startsAgainWhenNetworkReturns(TransferState.WAITING_FOR_NETWORK))
    }

    @Test
    fun `nothing else is revived by wifi coming back either`() {
        for (state in TransferState.entries) {
            val expected = state == TransferState.WAITING_FOR_NETWORK
            assertEquals(
                "$state",
                expected,
                startsAgainWhenNetworkReturns(state),
            )
        }
    }

    @Test
    fun `losing wifi holds back exactly what the queue would have run`() {
        assertTrue(waitsForNetwork(TransferState.PENDING))
        assertTrue(waitsForNetwork(TransferState.RUNNING))
        assertTrue(waitsForNetwork(TransferState.INTERRUPTED))
    }

    /**
     * Moving a user-paused transfer into the network wait would hand it back
     * to the queue the moment Wi-Fi returned -- the same bug by another route.
     */
    @Test
    fun `losing wifi does not touch a paused, finished or failed transfer`() {
        assertFalse(waitsForNetwork(TransferState.PAUSED))
        assertFalse(waitsForNetwork(TransferState.COMPLETED))
        assertFalse(waitsForNetwork(TransferState.FAILED))
        assertFalse(waitsForNetwork(TransferState.WAITING_FOR_NETWORK))
    }
}
