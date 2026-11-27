package com.example.myvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiHandoverTest {
    private val direct = HomeRoute()
    private val node = HomeRoute(SubscriptionParser.parse(
        "vless://00000000-0000-0000-0000-000000000003@node.example:443?security=tls",
    ).single())

    @Test fun onlyTransitionsInvolvingWifiTriggerComparison() {
        val mobile = WifiHandover.NetworkState(1, false)
        val otherMobile = WifiHandover.NetworkState(2, false)
        val wifi = WifiHandover.NetworkState(3, true)
        val otherWifi = WifiHandover.NetworkState(4, true)
        assertFalse(mobile.changedWifiFrom(null))
        assertFalse(wifi.changedWifiFrom(null))
        assertFalse(otherMobile.changedWifiFrom(mobile))
        assertFalse(wifi.changedWifiFrom(wifi.copy()))
        assertTrue(wifi.changedWifiFrom(mobile))
        assertTrue(mobile.changedWifiFrom(wifi))
        assertTrue(otherWifi.changedWifiFrom(wifi))
    }

    @Test fun comparesExactlyTwoRoutesEvenWhenDirectExceedsTenMbps() {
        val calls = mutableListOf<HomeRoute>()
        val result = WifiHandover.compare(direct, node) { route ->
            calls += route
            if (route == direct) NodeSpeed(30.0, null, 2000) else NodeSpeed(28.0, null, 500)
        }
        assertEquals(listOf(direct, node), calls)
        assertEquals(node, result.best)
    }

    @Test fun keepsCurrentRouteOnExactTie() {
        assertEquals(node, WifiHandover.compare(node, node) { NodeSpeed(20.0, null, 80) }.best)
    }

    @Test fun noWinnerIfBothRoutesFail() {
        assertNull(WifiHandover.compare(node, node) { null }.best)
    }

    @Test fun noKnownNodeMeansOnlyDirectIsMeasured() {
        val calls = mutableListOf<HomeRoute>()
        val result = WifiHandover.compare(direct, null) { route ->
            calls += route
            NodeSpeed(0.5, null, 80)
        }
        assertEquals(listOf(direct), calls)
        assertEquals(direct, result.best)
    }
}
