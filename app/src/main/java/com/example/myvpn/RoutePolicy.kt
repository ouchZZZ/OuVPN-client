package com.example.myvpn

internal object RoutePolicy {
    const val HOME_CANDIDATES = 5
    const val SLOW_MBPS = 1.0
    private const val DIRECT_WIFI_MBPS = 10.0
    private const val RESPONSIVE_MIN_MBPS = 10.0
    private const val LATENCY_WEIGHT_MS = 3000.0

    fun fastDirect(wifi: Boolean, exhaustive: Boolean, speed: NodeSpeed?): Boolean =
        wifi && !exhaustive && speed != null && speed.downloadMbps >= DIRECT_WIFI_MBPS

    fun <T> shortlist(scores: Map<T, NodeSpeed>): List<T> = ranked(scores).take(HOME_CANDIDATES)

    fun <T> ranked(scores: Map<T, NodeSpeed>): List<T> = scores.entries.sortedWith(
        compareByDescending<Map.Entry<T, NodeSpeed>> { it.value.downloadMbps >= RESPONSIVE_MIN_MBPS }
            .thenByDescending { (_, speed) ->
                if (speed.downloadMbps < RESPONSIVE_MIN_MBPS) speed.downloadMbps
                else speed.downloadMbps / (1 + speed.latencyMs / LATENCY_WEIGHT_MS)
            }.thenByDescending { it.value.downloadMbps },
    ).map { it.key }
}
