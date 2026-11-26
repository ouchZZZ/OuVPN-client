package com.example.myvpn

import android.content.Context
import com.wireguard.config.Config
import java.net.InetSocketAddress

internal data class HomeRoute(val node: ProxyNode? = null) {
    val key: String get() = node?.link ?: DIRECT
    val label: String get() = node?.let { "через ${it.name}" } ?: "напрямую"
    companion object { const val DIRECT = "direct" }
}

internal data class RouteReport(
    val ping: Map<String, Long?> = emptyMap(),
    val outer: Map<String, NodeSpeed?> = emptyMap(),
    val home: Map<String, NodeSpeed?> = emptyMap(),
    val best: HomeRoute? = null,
    val egress: EgressMode = EgressMode.HOME,
)

internal class RouteTester(private val context: Context, config: Config) {
    private val iface = config.getInterface()
    private val peer = config.peers.single()
    val endpoint = peer.endpoint.get()
    val spec = SingBoxConfig.HomeProbe(endpoint.host, endpoint.port, iface.addresses.map { it.toString() },
        iface.keyPair.privateKey.toBase64(), peer.publicKey.toBase64(), peer.preSharedKey.orElse(null)?.toBase64(), iface.mtu.orElse(1280))

    fun home(route: HomeRoute, light: Boolean = false, includeUpload: Boolean = false): NodeSpeed? =
        attempt { SpeedCheck.home(context, spec, route.node, light, includeUpload) }

    fun scan(
        nodes: List<ProxyNode>, wifi: Boolean, exhaustive: Boolean,
        sampleHome: (HomeRoute) -> NodeSpeed?, progress: (String) -> Unit,
        knownDirect: Pair<HomeRoute, NodeSpeed?>? = null,
        includeUpload: Boolean = false,
    ): RouteReport {
        val direct = HomeRoute()
        val home = linkedMapOf<String, NodeSpeed?>()
        if (wifi || knownDirect != null) {
            progress("Проверяем прямой WireGuard…")
            home[direct.key] = if (knownDirect != null) knownDirect.second else sampleHome(direct)
            if (RoutePolicy.fastDirect(wifi, exhaustive, home[direct.key])) return RouteReport(home = home, best = direct)
        }

        val labels = nodes.associateBy { it.link }
        val survey = NodeSurvey.run(context, nodes, progress, includeUpload)
        val outer = survey.outer
        if (!home.containsKey(direct.key)) {
            progress("Проверяем прямой WireGuard…")
            home[direct.key] = sampleHome(direct)
        }
        val short = RoutePolicy.shortlist(outer.mapNotNull { (key, speed) -> speed?.let { key to it } }.toMap())
        short.forEachIndexed { index, key ->
            NodeSurvey.interrupted()
            progress("Замер через WireGuard · ${index + 1}/${short.size}")
            home[key] = sampleHome(HomeRoute(labels.getValue(key)))
        }
        val best = RoutePolicy.ranked(home.mapNotNull { (key, speed) -> speed?.let { key to it } }.toMap()).firstOrNull()
            ?.let { HomeRoute(labels[it]) }
        return RouteReport(survey.ping, outer, home, best)
    }

    fun transport(route: HomeRoute): RouteTransport = if (route.node == null) {
        val address = java.net.InetAddress.getAllByName(endpoint.host).sortedBy { it is java.net.Inet6Address }.first()
        RouteTransport(InetSocketAddress(address, endpoint.port), null)
    } else {
        val process = SingBoxProcess.relay(context, route.node, endpoint.host, endpoint.port)
        RouteTransport(InetSocketAddress("127.0.0.1", process.port), process)
    }

    private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
    private fun <T> attempt(run: () -> T): T? = try { interrupted(); run() }
        catch (e: InterruptedException) { throw e }
        catch (_: Exception) { null }
}

internal class RouteTransport(val address: InetSocketAddress, private val core: SingBoxProcess?) : AutoCloseable {
    val alive: Boolean get() = core?.alive ?: true
    override fun close() { core?.close() }
}
