package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCandidatesTest {
    @Test
    fun fallbackUsesRemainingNetworkWhenCurrentIsLost() {
        val networks = NetworkCandidates<String, String>()
        networks.onAvailable("wifi", multiple = true)
        networks.onUpdate("wifi", "wlan0")
        networks.onAvailable("cellular", multiple = true)

        assertTrue(networks.onLost("cellular"))
        assertEquals("wifi", networks.current)
        assertEquals("wlan0", networks.currentLink)
    }

    @Test
    fun losingAnotherNetworkDoesNotChangeSelection() {
        val networks = NetworkCandidates<String, String>()
        networks.onAvailable("wifi", multiple = true)
        networks.onAvailable("cellular", multiple = true)

        assertFalse(networks.onLost("wifi"))
        assertEquals("cellular", networks.current)
        assertTrue(networks.onLost("cellular"))
        assertNull(networks.current)
    }

    @Test
    fun bestNetworkModeDoesNotRestorePreviousBest() {
        val networks = NetworkCandidates<String, String>()
        networks.onAvailable("wifi", multiple = false)
        networks.onAvailable("cellular", multiple = false)

        assertTrue(networks.onLost("cellular"))
        assertNull(networks.current)
    }
}
