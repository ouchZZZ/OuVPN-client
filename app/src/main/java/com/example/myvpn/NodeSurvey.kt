package com.example.myvpn

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal object NodeSurvey {
    private const val PARALLEL_PINGS = TimedSpeedProbe.PARALLEL_PINGS

    fun run(context: Context, nodes: List<ProxyNode>, progress: (String) -> Unit, includeUpload: Boolean = false): RouteReport =
        run(nodes, progress, ping = { SpeedCheck.ping(context, it) },
            measure = { SpeedCheck.measure(context, it, includeUpload = includeUpload) })

    internal fun run(nodes: List<ProxyNode>, progress: (String) -> Unit,
        ping: (ProxyNode) -> Long?, measure: (ProxyNode) -> NodeSpeed?): RouteReport {
        val candidates = nodes.distinctBy { it.link }
        val pings = linkedMapOf<String, Long?>()
        val pool = Executors.newFixedThreadPool(PARALLEL_PINGS)
        try {
            for (batch in candidates.chunked(PARALLEL_PINGS)) {
                interrupted()
                progress("Проверка доступности · ${pings.size + 1}–${pings.size + batch.size}/${candidates.size}")
                val results = pool.invokeAll(batch.map { node -> Callable { attempt { ping(node) } } })
                batch.zip(results).forEach { (node, result) -> pings[node.link] = result.get() }
            }
        } finally { pool.shutdownNow() }
        val reachable = candidates.filter { pings[it.link] != null }
        val speeds = linkedMapOf<String, NodeSpeed?>()
        reachable.forEachIndexed { index, node ->
            interrupted()
            progress("Замер скорости · ${index + 1}/${reachable.size}")
            speeds[node.link] = attempt { measure(node) }
        }
        val bestKey = RoutePolicy.ranked(speeds.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()).firstOrNull()
        val best = candidates.firstOrNull { it.link == bestKey }?.let(::HomeRoute)
        return RouteReport(pings, speeds, best = best, egress = EgressMode.NODE)
    }

    fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }

    fun <T> attempt(block: () -> T): T? = try { interrupted(); block() }
        catch (e: InterruptedException) { throw e }
        catch (_: Exception) { null }
}

internal interface VpnController : AutoCloseable {
    fun run(request: MyVpnService.Selection?, fullCheck: Boolean)
    fun onNetworkChanged()
    fun health()
}
