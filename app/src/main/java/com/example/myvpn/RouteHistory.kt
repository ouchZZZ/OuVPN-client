package com.example.myvpn

import org.json.JSONObject

internal object RouteHistory {
    fun encode(report: RouteReport): String = JSONObject()
        .put("ping", JSONObject().apply { report.ping.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } })
        .put("outer", encodeSpeeds(report.outer)).put("home", encodeSpeeds(report.home)).toString()

    fun decode(encoded: String): RouteReport {
        val data = JSONObject(encoded)
        val ping = data.getJSONObject("ping")
        return RouteReport(ping.keys().asSequence().associateWith { if (ping.isNull(it)) null else ping.getLong(it) },
            decodeSpeeds(data.getJSONObject("outer")), decodeSpeeds(data.getJSONObject("home")))
    }

    fun merge(previous: RouteReport, latest: RouteReport): RouteReport = latest.copy(
        ping = previous.ping + latest.ping,
        outer = previous.outer.filterKeys { it !in latest.ping } + latest.outer,
        home = previous.home + latest.home,
    )

    fun best(history: RouteReport, nodes: List<ProxyNode>, mode: EgressMode): HomeRoute? {
        val routes = nodes.map { HomeRoute(it) }
        val candidates = if (mode == EgressMode.HOME) listOf(HomeRoute()) + routes else routes.filter {
            SingBoxConfig.supportsNodeTunnel(checkNotNull(it.node))
        }
        val speeds = if (mode == EgressMode.HOME) history.home else history.outer
        return RoutePolicy.ranked(candidates.mapNotNull { route -> speeds[route.key]?.let { route to it } }.toMap()).firstOrNull()
    }

    fun <T : Any> tryCached(candidate: T?, probe: (T) -> Boolean, scan: () -> T?): T? =
        if (candidate != null && probe(candidate)) candidate else scan()

    private fun encodeSpeeds(speeds: Map<String, NodeSpeed?>) = JSONObject().apply {
        speeds.forEach { (key, speed) -> put(key, speed?.let {
            JSONObject().put("down", it.downloadMbps).put("up", it.uploadMbps ?: JSONObject.NULL).put("ms", it.latencyMs)
        } ?: JSONObject.NULL) }
    }

    private fun decodeSpeeds(data: JSONObject): Map<String, NodeSpeed?> = data.keys().asSequence().associateWith { key ->
        data.optJSONObject(key)?.let { NodeSpeed(it.getDouble("down"), if (it.isNull("up")) null else it.getDouble("up"), it.getLong("ms")) }
    }
}
