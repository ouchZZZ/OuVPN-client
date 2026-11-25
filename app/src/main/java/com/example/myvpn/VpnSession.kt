package com.example.myvpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface as WireGuardInterface
import com.wireguard.config.Peer

internal class VpnSession(
    private val context: Context,
    private val publish: (MyVpnService.Status) -> Unit,
    private val report: (RouteReport) -> Unit,
    private val stop: () -> Unit,
) : VpnController {
    private val backend by lazy { GoBackend(context) }
    private var relay: LocalWireGuardRelay? = null
    private var transport: RouteTransport? = null
    private var connected: HomeRoute? = null
    private var pinned: HomeRoute? = null
    private var rxAtSwitch = 0L
    private var switchedAtWall = 0L
    private var tester: RouteTester? = null
    private var mode = MyVpnService.Mode.TEST
    private var network: Network? = null
    private var retryAfter = 0L
    private var routeAt = 0L
    private var lastNotice: String? = null
    private var peer: Peer? = null
    private var imported: Config? = null
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val tunnel = object : Tunnel {
        override fun getName() = "HomeWireGuard"
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    override fun run(request: MyVpnService.Selection?, fullCheck: Boolean) {
        lastNotice = null
        try {
            if (fullCheck) {
                val probe = tester ?: return
                val original = connected
                try { scan(probe, exhaustive = true, includeUpload = true) }
                finally {
                    if (original != null && !Thread.currentThread().isInterrupted) restore(original)
                }
                lastNotice = "Полная проверка завершена"
                stable()
                return
            }
            checkNotNull(request)
            if (request.mode == MyVpnService.Mode.TEST) {
                val probe = RouteTester(context, checkNotNull(request.config))
                scan(probe, exhaustive = true, includeUpload = true)
                publish(MyVpnService.Status(false, "Полная проверка завершена"))
                stop()
                return
            }
            if (relay == null) startTunnel(request)
            // When a tunnel already exists, keep its validated profile and app routes until a user reconnects.
            check(imported == request.config) { "WireGuard profile changed" }
            mode = request.mode
            pinned = if (mode == MyVpnService.Mode.MANUAL) request.route else null
            if (mode == MyVpnService.Mode.AUTO) connectAuto()
            else {
                val route = checkNotNull(pinned)
                show("Подключаем WireGuard ${route.label}…", true)
                restore(route)
                stable()
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            if (relay == null || peer == null) {
                publish(MyVpnService.Status(false, "Не удалось запустить WireGuard или проверку"))
                stop()
            } else {
                lastNotice = "Не удалось завершить операцию"
                stable()
            }
        }
    }

    private fun startTunnel(request: MyVpnService.Selection) {
        val input = checkNotNull(request.config)
        val source = input.getInterface()
        val sourcePeer = input.peers.single()
        val local = LocalWireGuardRelay()
        try {
            val iface = WireGuardInterface.Builder().setKeyPair(source.keyPair).addAddresses(source.addresses)
                .addDnsServers(source.dnsServers).addDnsSearchDomains(source.dnsSearchDomains)
                .excludeApplication(context.packageName).setMtu(source.mtu.orElse(1280))
            val installed = request.excluded.filter { pkg ->
                runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
            }
            iface.excludeApplications(installed)
            if (source.dnsServers.isEmpty()) iface.parseDnsServers("1.1.1.1")
            val destination = Peer.Builder().setPublicKey(sourcePeer.publicKey).parseEndpoint("127.0.0.1:${local.port}")
                .parseAllowedIPs("0.0.0.0/0, ::/0").setPersistentKeepalive(sourcePeer.persistentKeepalive.orElse(15).takeIf { it > 0 } ?: 15)
            sourcePeer.preSharedKey.ifPresent { destination.setPreSharedKey(it) }
            val config = Config.Builder().setInterface(iface.build()).addPeer(destination.build()).build()
            val probe = RouteTester(context, input)
            backend.setState(tunnel, Tunnel.State.UP, config)
            peer = config.peers.single()
            relay = local
            tester = probe
            imported = input
            network = connectivity.activeNetwork
        } catch (error: Exception) { local.close(); throw error }
    }

    override fun onNetworkChanged() {
        if (relay == null) return
        val current = connectivity.activeNetwork ?: return
        if (network == current) return
        network = current
        val previous = connected
        try {
            if (mode == MyVpnService.Mode.MANUAL) {
                pinned?.let(::restore) // New transport, same manually pinned route.
                stable()
                return
            }
            if (previous == null) { choose(); return }
            var knownDirect: Pair<HomeRoute, NodeSpeed?>? = null
            if (isWifi()) {
                val direct = HomeRoute()
                show("Wi‑Fi: проверяем прямой WireGuard…", true)
                val measured = sample(direct)
                knownDirect = direct to measured
                if (RoutePolicy.fastDirect(wifi = true, exhaustive = false, speed = measured)) {
                    restore(direct); stable(); return
                }
            }
            show("Сеть изменилась: проверяем текущий маршрут…", true)
            val first = if (previous.node == null && knownDirect != null) knownDirect.second else sample(previous, light = true)
            val second = first?.takeIf { it.downloadMbps < RoutePolicy.SLOW_MBPS }?.let { sample(previous, light = true) }
            if (RoutePolicy.needsReselection(first, second)) choose(knownDirect)
            else { restore(previous); stable() }
        } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        catch (_: Exception) { stable() }
    }

    override fun health() {
        if (relay == null) return
        if (backend.getState(tunnel) != Tunnel.State.UP) { stop(); return }
        val age = SystemClock.elapsedRealtime() - routeAt
        val stale = connected == null || transport?.alive != true || (age > 35_000 && !confirmed())
        if (mode == MyVpnService.Mode.AUTO && stale && SystemClock.elapsedRealtime() >= retryAfter) {
            try { choose() } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            catch (_: Exception) { stable() }
        } else stable()
    }

    private fun connectAuto() {
        val config = checkNotNull(imported)
        val history = runCatching { SavedVpnState.loadReport(context, config) }.getOrDefault(RouteReport())
        val nodes = SubscriptionRepository.list(context).flatMap { it.namedNodes() }
        val cached = RouteHistory.best(history, nodes, EgressMode.HOME)
        RouteHistory.tryCached(cached, { route ->
            show("Короткая проверка последнего лучшего маршрута…", true)
            val speed = sample(route, light = true)
            val ready = speed != null && speed.downloadMbps >= RoutePolicy.SLOW_MBPS &&
                NodeSurvey.attempt { restore(route); true } == true
            if (ready) {
                retryAfter = SystemClock.elapsedRealtime() + 90_000
                stable()
            } else {
                report(RouteReport(home = mapOf(route.key to null)))
            }
            ready
        }, { choose(exhaustive = true); connected })
    }

    private fun choose(knownDirect: Pair<HomeRoute, NodeSpeed?>? = null, exhaustive: Boolean = false) {
        retryAfter = SystemClock.elapsedRealtime() + 90_000
        val outcome = try { scan(checkNotNull(tester), exhaustive, knownDirect) }
            finally { retryAfter = SystemClock.elapsedRealtime() + 90_000 }
        val best = outcome.best
        if (best == null) {
            suspendPackets()
            connected = null
        } else {
            restore(best)
            lastNotice = outcome.home[best.key]?.takeIf { it.downloadMbps < RoutePolicy.SLOW_MBPS }?.let { "Ниже 1 Мбит/с" }
        }
        stable()
    }

    private fun scan(probe: RouteTester, exhaustive: Boolean, knownDirect: Pair<HomeRoute, NodeSpeed?>? = null, includeUpload: Boolean = false): RouteReport {
        var paused = false
        val nodes = SubscriptionRepository.list(context).flatMap { it.namedNodes() }
        val result = probe.scan(nodes, isWifi(), exhaustive, { route ->
            if (!paused) { suspendPackets(); paused = true }
            probe.home(route, includeUpload = includeUpload)
        }, { text -> show(text, true) }, knownDirect, includeUpload)
        report(result)
        return result
    }

    private fun sample(route: HomeRoute, light: Boolean = false): NodeSpeed? {
        suspendPackets()
        return checkNotNull(tester).home(route, light)
    }

    private fun suspendPackets() {
        relay?.route(null)
        transport?.close()
        transport = null
    }

    private fun restore(route: HomeRoute) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val local = checkNotNull(relay)
        val ready = checkNotNull(tester).transport(route)
        val before = peerStats()
        try { local.route(ready.address) } catch (e: Exception) { ready.close(); throw e }
        transport?.close()
        transport = ready
        connected = route
        routeAt = SystemClock.elapsedRealtime()
        rxAtSwitch = before?.rxBytes() ?: 0L
        switchedAtWall = System.currentTimeMillis()
    }

    private fun peerStats(): com.wireguard.android.backend.Statistics.PeerStats? {
        val key = peer?.publicKey ?: return null
        return runCatching { backend.getStatistics(tunnel).peer(key) }.getOrNull()
    }

    private fun confirmed(): Boolean {
        val stats = peerStats() ?: return false
        val handshake = stats.latestHandshakeEpochMillis()
        return handshake > 0 && System.currentTimeMillis() - handshake < 175_000 &&
            (handshake >= switchedAtWall || stats.rxBytes() > rxAtSwitch)
    }

    private fun stable() {
        val route = connected
        val text = when {
            route == null -> pinned?.let { "Нет связи: WireGuard ${it.label}" } ?: "Нет маршрута до дома"
            transport?.alive != true -> "Нет связи: WireGuard ${route.label}"
            confirmed() -> "WireGuard ${route.label}"
            SystemClock.elapsedRealtime() - routeAt < 22_000 -> "WireGuard ${route.label} · ждём ответ"
            else -> "Нет ответа от дома · ${route.label}"
        }
        show(listOfNotNull(text, lastNotice).joinToString("\n"), false)
    }

    private fun show(text: String, busy: Boolean) {
        val current = connected
        val description = if (busy && current != null) "$text\nТекущий: WireGuard ${current.label}" else text
        publish(MyVpnService.Status(relay != null, description, busy, if (relay != null) mode else null, current?.key))
    }

    private fun isWifi(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    override fun close() {
        suspendPackets()
        relay?.close()
        relay = null
        if (peer != null) runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
    }
}
