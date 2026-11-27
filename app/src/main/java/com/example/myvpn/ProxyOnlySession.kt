package com.example.myvpn

import android.content.Context


/** Plain subscription-node mode: do not instantiate WireGuard or read its profile. */
internal class ProxyOnlySession(
    private val context: Context,
    private val publish: (MyVpnService.Status) -> Unit,
    private val report: (RouteReport) -> Unit,
    private val stop: () -> Unit,
) : VpnController {
    private var tun: ProxyTun? = null
    private var core: DirectTunProcess? = null
    private var current: ProxyNode? = null
    private var pinned: ProxyNode? = null
    private var mode = MyVpnService.Mode.AUTO
    private var notice: String? = null

    override fun run(request: MyVpnService.Selection?, fullCheck: Boolean) = guarded {
        notice = null
        if (fullCheck || request?.mode == MyVpnService.Mode.TEST) {
            scan(includeUpload = true)
            if (tun == null) {
                publish(MyVpnService.Status(false, "Проверка нод завершена", egress = EgressMode.NODE))
                stop()
            } else {
                notice = "Проверка нод завершена"
                status()
            }
            return@guarded
        }
        checkNotNull(request)
        check(request.config == null)
        if (tun == null) {
            val eligible = if (request.mode == MyVpnService.Mode.MANUAL) {
                request.route?.node?.let(SingBoxConfig::supportsNodeTunnel) == true
            } else nodes().isNotEmpty()
            if (!eligible) {
                publish(MyVpnService.Status(false, "Нет нод, подходящих для подключения без WireGuard", egress = EgressMode.NODE))
                stop()
                return@guarded
            }
            tun = ProxyTunService.open(context, request.excluded, stop)
        }
        mode = request.mode
        pinned = request.route?.node.takeIf { mode == MyVpnService.Mode.MANUAL }
        if (mode == MyVpnService.Mode.AUTO) connectAuto()
        else activate(checkNotNull(pinned))
        status()
    }

    override fun health() {
        if (tun != null && core != null && !alive()) {
            pause()
            status()
        }
    }

    private fun nodes() = SubscriptionRepository.list(context).flatMap { it.namedNodes() }
        .filter(SingBoxConfig::supportsNodeTunnel).distinctBy { it.link }

    private fun scan(includeUpload: Boolean = false): Pair<RouteReport, Map<String, ProxyNode>> {
        val nodes = nodes()
        val outcome = NodeSurvey.run(context, nodes, ::busy, includeUpload)
        report(outcome)
        return outcome to nodes.associateBy { it.link }
    }

    private fun connectAuto() {
        val history = runCatching { SavedVpnState.loadReport(context, null) }.getOrDefault(RouteReport())
        val cached = RouteHistory.best(history, nodes(), EgressMode.NODE)?.node
        RouteHistory.tryCached(cached, { node ->
            busy("Короткая проверка последней лучшей ноды…")
            val speed = NodeSurvey.attempt { SpeedCheck.measure(context, node, light = true) }
            val ready = speed != null && speed.downloadMbps >= RoutePolicy.SLOW_MBPS &&
                NodeSurvey.attempt { activate(node); true } == true
            if (!ready) report(RouteReport(outer = mapOf(node.link to null), egress = EgressMode.NODE))
            ready
        }, { choose(); current })
    }

    private fun choose() {
        notice = null
        busy("Ищем рабочую ноду…")
        val (outcome, nodes) = scan()
        val speeds = outcome.outer.mapNotNull { (key, score) -> score?.let { key to it } }.toMap()
        val candidates = RoutePolicy.ranked(speeds).mapNotNull(nodes::get)
        for (node in candidates) {
            if (NodeSurvey.attempt { activate(node); true } == true) {
                if (speeds[node.link]?.downloadMbps?.let { it < RoutePolicy.SLOW_MBPS } == true) notice = "Ниже 1 Мбит/с"
                return
            }
        }
        pause()
    }

    private fun activate(node: ProxyNode) {
        NodeSurvey.interrupted()
        val device = checkNotNull(tun)
        busy("Подключаем ноду: ${node.name}")
        pause()
        val candidate = DirectTunProcess.start(context, device.fd, node)
        core = candidate
        try {
            candidate.verifyEgress()
        } catch (e: Exception) {
            pause()
            throw e
        }
        current = node
    }

    private fun pause() {
        current = null
        core?.close()
        core = null
    }

    private fun verifyActive() {
        checkNotNull(core).verifyEgress()
    }

    private fun alive() = core?.alive == true && tun != null

    private fun busy(text: String) {
        val name = current?.name
        val label = if (name != null) "$text\nТекущая нода: $name" else text
        publish(MyVpnService.Status(tun != null, label, true, mode.takeIf { tun != null }, current?.link, EgressMode.NODE))
    }

    private fun status() {
        val selected = current
        val text = when {
            selected != null && alive() -> "Нода: ${selected.name}"
            pinned != null -> "Нет связи с нодой: ${pinned?.name}"
            else -> "Нет рабочего маршрута через ноду"
        }
        publish(MyVpnService.Status(tun != null, listOfNotNull(text, notice).joinToString("\n"),
            false, mode.takeIf { tun != null }, selected?.link, EgressMode.NODE))
    }

    private fun guarded(action: () -> Unit) {
        try { action() }
        catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        catch (e: Exception) {
            notice = if (tun == null && android.os.Build.VERSION.SDK_INT < 29) "Режим через ноду требует Android 10 или новее."
                else "Не удалось запустить подключение или проверку ноды"
            if (tun == null) {
                publish(MyVpnService.Status(false, checkNotNull(notice), egress = EgressMode.NODE))
                stop()
            } else {
                status()
            }
        }
    }

    override fun close() {
        try { pause() } finally { tun?.close(); tun = null }
    }
}
