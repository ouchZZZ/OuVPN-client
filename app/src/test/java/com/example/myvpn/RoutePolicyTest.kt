package com.example.myvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePolicyTest {
    @Test fun wifiDirectShortcutAppliesOnlyToAutomaticSelection() {
        assertTrue(RoutePolicy.fastDirect(wifi = true, exhaustive = false, speed = score(10.0)))
        assertFalse(RoutePolicy.fastDirect(wifi = true, exhaustive = true, speed = score(100.0)))
        assertFalse(RoutePolicy.fastDirect(wifi = false, exhaustive = false, speed = score(100.0)))
        assertFalse(RoutePolicy.fastDirect(wifi = true, exhaustive = false, speed = score(9.9)))
    }

    @Test fun candidateLimitCountsNodesSeparatelyFromTheDirectRoute() {
        val candidates = (1..8).associateWith { score(it.toDouble()) }
        assertEquals(listOf(8, 7, 6, 5, 4), RoutePolicy.shortlist(candidates))
    }

    @Test fun belowTenOnlyDownloadSpeedMatters() {
        assertEquals("down", RoutePolicy.ranked(mapOf("down" to score(5.0, 1.0, 2000), "upload" to score(4.9, 3.0, 500))).first())
        assertEquals("slow", RoutePolicy.ranked(mapOf("slow" to score(0.6))).first())
    }

    @Test fun onceSpeedIsSufficientLatencyBreaksCloseSpeedDifferences() {
        assertEquals(listOf("very-fast", "responsive", "slower-response", "under-threshold"), RoutePolicy.ranked(mapOf(
            "slower-response" to score(30.0, ms = 2000), "responsive" to score(28.0, ms = 500),
            "very-fast" to score(45.0, ms = 2000), "under-threshold" to score(9.9, ms = 1),
        )))
        assertEquals("minimum", RoutePolicy.ranked(mapOf("minimum" to score(10.0, ms = 2000), "below" to score(9.9, ms = 1))).first())
    }

    private fun score(down: Double, up: Double = 1.0, ms: Long = 30) = NodeSpeed(down, up, ms)
}
