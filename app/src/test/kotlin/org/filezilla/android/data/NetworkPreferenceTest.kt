package org.filezilla.android.data

import androidx.test.core.app.ApplicationProvider
import org.filezilla.android.transfer.NetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkPreferenceTest {

    private val preferences = AppPreferences(ApplicationProvider.getApplicationContext())

    /**
     * Off unless asked for. Turning it on for someone with no Wi-Fi would stop
     * their transfers with no cause they could see.
     */
    @Test
    fun `wifi only is off to begin with`() {
        assertFalse(preferences.wifiOnly)
        assertEquals(NetworkPolicy.ANY, preferences.networkPolicy)
    }

    /**
     * The setting and the policy the queue reads have to agree. They are read
     * in different places -- the switch, the gate, and whatever asks whether a
     * transfer could run right now -- and a disagreement between them is how a
     * transfer ends up waiting for a network it already has.
     */
    @Test
    fun `the saved setting is the policy the queue reads`() {
        preferences.wifiOnly = true
        assertEquals(NetworkPolicy.UNMETERED, preferences.networkPolicy)

        preferences.wifiOnly = false
        assertEquals(NetworkPolicy.ANY, preferences.networkPolicy)
    }

    @Test
    fun `the setting survives being read back`() {
        preferences.wifiOnly = true

        val reopened = AppPreferences(ApplicationProvider.getApplicationContext())

        assertEquals(true, reopened.wifiOnly)
        assertEquals(NetworkPolicy.UNMETERED, reopened.networkPolicy)
    }
}
