package com.example.myvpn

internal object WifiHandover {
    data class NetworkState(val handle: Long, val wifi: Boolean) {
        fun changedWifiFrom(previous: NetworkState?): Boolean =
            previous != null && previous != this && (previous.wifi || wifi)
    }

    fun compare(current: HomeRoute?, bestNode: HomeRoute?, sample: (HomeRoute) -> NodeSpeed?): RouteReport {
        val routes = listOfNotNull(HomeRoute(), bestNode?.takeIf { it.node != null }).distinctBy { it.key }
        val home = routes.associate { it.key to sample(it) }
        // Give the current route first place only when the regular ranking cannot break a tie.
        val scores = routes.sortedByDescending { it.key == current?.key }
            .mapNotNull { route -> home[route.key]?.let { route to it } }.toMap()
        return RouteReport(home = home, best = RoutePolicy.ranked(scores).firstOrNull())
    }
}
