package com.example.myvpn

internal object RoutePolicy {
    const val QUICK_TIMEOUT_MS = 1_500L
    const val QUICK_PARALLELISM = 4
    const val HOME_CANDIDATES = 5
    const val SLOW_MBPS = 1.0
    private const val DIRECT_WIFI_MBPS = 10.0

    fun fastDirect(wifi: Boolean, exhaustive: Boolean, speed: NodeSpeed?): Boolean =
        wifi && !exhaustive && speed != null && speed.downloadMbps >= DIRECT_WIFI_MBPS

    fun needsReselection(first: NodeSpeed?, repeat: NodeSpeed?): Boolean =
        first == null || (first.downloadMbps < SLOW_MBPS && (repeat == null || repeat.downloadMbps < SLOW_MBPS))

    fun <T> shortlist(scores: Map<T, NodeSpeed>): List<T> = scores.entries.sortedByDescending { it.value.downloadMbps }
        .take(HOME_CANDIDATES).map { it.key }

    fun <T> ranked(scores: Map<T, NodeSpeed>): List<T> {
        val pending = scores.entries.sortedByDescending { it.value.downloadMbps }.toMutableList()
        return buildList {
            while (pending.isNotEmpty()) {
                val near = pending.first().value.downloadMbps * 0.95
                val group = pending.takeWhile { it.value.downloadMbps >= near }
                addAll(group.sortedWith(compareByDescending<Map.Entry<T, NodeSpeed>> { it.value.uploadMbps ?: -1.0 }
                    .thenBy { it.value.latencyMs }.thenByDescending { it.value.downloadMbps }).map { it.key })
                repeat(group.size) { pending.removeAt(0) }
            }
        }
    }
}
