package com.example.myvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteHistoryTest {
    private val slow = node("first.example")
    private val fast = node("second.example")
    private val removed = node("removed.example")
    private val speed = NodeSpeed(10.0, 3.0, 109)
    private val original = RouteReport(
        ping = mapOf(fast.link to 109L, slow.link to 8L, removed.link to 2L),
        outer = mapOf(fast.link to speed, slow.link to NodeSpeed(9.9, 12.0, 8), removed.link to speed.copy(downloadMbps = 20.0)),
        home = mapOf(HomeRoute.DIRECT to speed, fast.link to null),
    )

    @Test fun persistedMeasurementsRoundTripIncludingFailedRoutes() {
        assertEquals(original, RouteHistory.decode(RouteHistory.encode(original)))
    }

    @Test fun fastestEligibleRouteWinsWhenTheOtherSpeedsAreBelowThresholdOrRemoved() {
        assertEquals(fast, RouteHistory.best(original, listOf(slow, fast), EgressMode.NODE)?.node)
    }

    @Test fun cachedSelectionUsesTheSameLatencyPenaltyAsFreshSelection() {
        val saved = original.copy(outer = mapOf(fast.link to NodeSpeed(30.0, null, 2000), slow.link to NodeSpeed(28.0, null, 500)))
        assertEquals(slow, RouteHistory.best(saved, listOf(slow, fast), EgressMode.NODE)?.node)
    }

    @Test fun wireGuardUsesOnlyItsOwnScores() {
        assertEquals(HomeRoute(), RouteHistory.best(original, listOf(fast, slow), EgressMode.HOME))
        assertNull(RouteHistory.best(original.copy(home = emptyMap()), listOf(fast), EgressMode.HOME))
    }

    @Test fun partialUpdatesPreserveUnmeasuredRoutesButDropOldSpeedForADeadNode() {
        val newDirect = speed.copy(downloadMbps = 25.0)
        val directOnly = RouteHistory.merge(original, RouteReport(home = mapOf(HomeRoute.DIRECT to newDirect)))
        assertEquals(original.outer, directOnly.outer)
        assertEquals(newDirect, directOnly.home[HomeRoute.DIRECT])
        val failed = RouteHistory.merge(directOnly, RouteReport(ping = mapOf(fast.link to null)))
        assertNull(failed.outer[fast.link])
        assertEquals(original.outer[slow.link], failed.outer[slow.link])
    }

    @Test fun successfulCachedProbeDoesNotScanOtherNodes() {
        assertEquals(fast, RouteHistory.tryCached(fast, { true }, { error("Unexpected full scan") }))
    }

    @Test fun failedCachedProbeTriggersAFullScan() {
        assertEquals(slow, RouteHistory.tryCached(fast, { false }, { slow }))
    }

    private fun node(host: String) = SubscriptionParser.parse("vless://00000000-0000-0000-0000-000000000003@$host:443?security=tls").single()
}
