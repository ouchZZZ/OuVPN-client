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

    @Test fun slowRouteMustFailTheThresholdTwiceButMissingConnectivityDoesNotNeedARepeat() {
        assertTrue(RoutePolicy.needsReselection(null, null))
        assertFalse(RoutePolicy.needsReselection(score(0.9), score(1.0)))
        assertTrue(RoutePolicy.needsReselection(score(0.9), score(0.8)))
        assertFalse(RoutePolicy.needsReselection(score(1.0), null))
    }

    @Test fun candidateLimitCountsNodesSeparatelyFromTheDirectRoute() {
        val candidates = (1..8).associateWith { score(it.toDouble()) }
        assertEquals(listOf(8, 7, 6, 5, 4), RoutePolicy.shortlist(candidates))
    }

    @Test fun nearTiesPreferUploadWhileASlowButWorkingRouteCanStillWin() {
        assertEquals("upload", RoutePolicy.ranked(mapOf("down" to score(5.0, 1.0), "upload" to score(4.9, 3.0))).first())
        assertEquals("slow", RoutePolicy.ranked(mapOf("slow" to score(0.6))).first())
    }

    private fun score(down: Double, up: Double = 1.0) = NodeSpeed(down, up, 30)
}
