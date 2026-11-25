package com.example.myvpn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSurveyTest {
    @Test fun checksFiveAtOnceBeforeMeasuringOnlyRespondersSequentially() {
        val nodes = (0..6).map { i ->
            SubscriptionParser.parse("vless://00000000-0000-0000-0000-000000000003@node$i.example:443?security=tls").single()
        }
        val firstFive = CountDownLatch(5)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val completed = AtomicInteger()
        val measured = mutableListOf<ProxyNode>()
        val failures = setOf(nodes[1], nodes[5])
        val result = NodeSurvey.run(nodes, {}, ping = { node ->
            val running = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, running) }
            try {
                firstFive.countDown()
                assertTrue(firstFive.await(2, TimeUnit.SECONDS))
                if (node in failures) null else 420L
            } finally {
                active.decrementAndGet()
                completed.incrementAndGet()
            }
        }, measure = { node ->
            assertEquals(nodes.size, completed.get())
            measured.add(node)
            NodeSpeed(25.0, null, 300)
        })
        assertEquals(5, peak.get())
        assertEquals(nodes.filterNot { it in failures }, measured)
        assertEquals(420L, result.ping[nodes.first().link])
        assertTrue(result.ping.containsKey(nodes[1].link))
        assertNull(result.ping[nodes[1].link])
        assertEquals(300L, result.outer[nodes.first().link]?.latencyMs)
    }
}
