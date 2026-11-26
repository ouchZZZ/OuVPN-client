package com.example.myvpn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedSpeedProbeTest {
    @Test fun timeoutDoesNotAdmitAnotherBandwidthProbeWhileFirstStillRuns() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val secondRan = AtomicBoolean()
        try {
            TimedSpeedProbe(300).run(onTimeout = { null }) {
                started.countDown()
                try {
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { /* emulate blocking I/O */ }
                    }
                    true
                } finally { exited.countDown() }
            }
            assertEquals(0L, started.count)
            TimedSpeedProbe(1000).run(onTimeout = { null }) {
                secondRan.set(true)
                true
            }
            assertTrue("a bandwidth probe started before the prior work exited", !secondRan.get())
        } finally { release.countDown() }
        assertTrue(exited.await(1, TimeUnit.SECONDS))
    }

    @Test fun deadlineClosesOwnedTransportAndReturnsPartialMeasurement() {
        val closed = CountDownLatch(1)
        val started = CountDownLatch(1)
        val probe = TimedSpeedProbe(300)
        val result = probe.run(onTimeout = { if (started.count == 0L) "partial" else null }) {
            probe.own(AutoCloseable { closed.countDown() })
            started.countDown()
            CountDownLatch(1).await()
            "unreachable"
        }
        assertEquals("partial", result)
        assertTrue(closed.await(1, TimeUnit.SECONDS))
    }

    @Test fun partialRateUsesElapsedTimeFromFirstByteThroughDeadline() {
        val window = DownloadWindow(0, 3_000_000_000)
        window.record(1, 1_000_000_000)
        window.record(499_999, 2_000_000_000)
        window.record(900_000, 3_000_000_001)
        val sample = checkNotNull(window.sample(3_000_000_000, complete = false))
        assertEquals(2.0, sample.downloadMbps, 0.001)
        assertEquals(1000L, sample.latencyMs)
    }

    @Test fun tooLittleDataIsUnavailableNotZero() {
        val window = DownloadWindow(0, 5_000_000_000)
        assertNull(window.sample(5_000_000_000, complete = false))
        window.record(128, 1_000_000_000)
        assertNull(window.sample(5_000_000_000, complete = false))
    }
}
