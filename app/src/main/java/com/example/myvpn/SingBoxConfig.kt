package com.example.myvpn

import org.json.JSONArray
import org.json.JSONObject

internal object SingBoxConfig {
    const val BENCHMARK = "speed.cloudflare.com"
    const val TUN_IPV4 = "198.18.0.1"
    const val TUN_IPV4_PREFIX = 30
    const val TUN_IPV6 = "fd73:6f75:7670::1"
    const val TUN_IPV6_PREFIX = 126
    const val TUN_MTU = 1280

    fun relay(node: ProxyNode, port: Int, target: String, targetPort: Int): String = build(node,
        JSONObject().put("type", "direct").put("network", "udp")
            .put("override_address", target).put("override_port", targetPort), port, null)

    fun probe(node: ProxyNode, port: Int, serverAddress: String = node.host): String = build(node, JSONObject().put("type", "mixed"), port,
        JSONArray().put(JSONObject().put("domain", BENCHMARK).put("action", "route").put("outbound", "node"))
            .put(JSONObject().put("action", "reject")), serverAddress)

    fun supportsNodeTunnel(node: ProxyNode): Boolean = encryptedNode(node) &&
        try { outbound(node); true } catch (_: IllegalArgumentException) { false }

    private fun encryptedNode(node: ProxyNode): Boolean = when (node.protocol) {
        "hysteria2" -> true
        "vless" -> node.options["security"] in setOf("tls", "reality")
        else -> false
    }

    fun nodeTunnel(node: ProxyNode, serverAddress: String? = null): String {
        require(encryptedNode(node)) { "Для подключения без WireGuard требуется шифрование ноды." }
        val servers = JSONArray()
        if (serverAddress == null) servers.put(JSONObject().put("type", "local").put("tag", "bootstrap"))
        servers.put(JSONObject().put("type", "https").put("tag", "inside").put("server", "1.1.1.1").put("detour", "node"))
        val dns = JSONObject().put("final", "inside").put("servers", servers)
        val inbound = JSONObject().put("type", "tun").put("stack", "gvisor").put("auto_route", false)
            .put("mtu", TUN_MTU).put("address", JSONArray(listOf("$TUN_IPV4/$TUN_IPV4_PREFIX", "$TUN_IPV6/$TUN_IPV6_PREFIX")))
        val route = JSONObject().put("final", "node")
            .put("rules", JSONArray().put(JSONObject().put("port", 53).put("action", "hijack-dns")))
        if (serverAddress == null) route.put("default_domain_resolver", "bootstrap")
        return JSONObject().put("log", JSONObject().put("disabled", true)).put("dns", dns)
            .put("inbounds", JSONArray().put(inbound)).put("outbounds", JSONArray().put(outbound(node, serverAddress ?: node.host)))
            .put("route", route).toString()
    }

    internal class HomeProbe(
        val host: String, val port: Int, val addresses: List<String>, val privateKey: String,
        val publicKey: String, val preSharedKey: String?, val mtu: Int,
    )

    fun homeProbe(home: HomeProbe, node: ProxyNode?, port: Int): String {
        val peer = JSONObject().put("address", home.host).put("port", home.port).put("public_key", home.publicKey)
            .put("allowed_ips", JSONArray(listOf("0.0.0.0/0", "::/0")))
        home.preSharedKey?.let { peer.put("pre_shared_key", it) }
        val endpoint = JSONObject().put("type", "wireguard").put("tag", "home").put("system", false)
            .put("address", JSONArray(home.addresses)).put("private_key", home.privateKey).put("mtu", home.mtu)
            .put("peers", JSONArray().put(peer)).put("domain_resolver", "bootstrap")
        if (node != null) endpoint.put("detour", "node")
        val dns = JSONArray().put(JSONObject().put("type", "local").put("tag", "bootstrap"))
            .put(JSONObject().put("type", "https").put("tag", "inside").put("server", "1.1.1.1").put("detour", "home"))
        val rules = JSONArray()
            .put(JSONObject().put("domain", BENCHMARK).put("action", "resolve").put("server", "inside").put("strategy", "prefer_ipv4"))
            .put(JSONObject().put("domain", BENCHMARK).put("action", "route").put("outbound", "home"))
            .put(JSONObject().put("action", "reject"))
        val route = JSONObject().put("final", "home").put("default_domain_resolver", "bootstrap").put("rules", rules)
        val proxy = JSONObject().put("type", "mixed").put("listen", "127.0.0.1").put("listen_port", port)
        return JSONObject().put("log", JSONObject().put("disabled", true)).put("dns", JSONObject().put("servers", dns))
            .put("inbounds", JSONArray().put(proxy)).put("endpoints", JSONArray().put(endpoint))
            .put("outbounds", if (node != null) JSONArray().put(outbound(node)) else JSONArray())
            .put("route", route).toString()
    }

    private fun build(node: ProxyNode, inbound: JSONObject, port: Int, rules: JSONArray?, serverAddress: String = node.host): String {
        require(port in 1..65535)
        val route = JSONObject().put("final", "node").put("default_domain_resolver", "bootstrap")
        rules?.let { route.put("rules", it) }
        return JSONObject().put("log", JSONObject().put("disabled", true))
            .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "bootstrap"))))
            .put("inbounds", JSONArray().put(inbound.put("listen", "127.0.0.1").put("listen_port", port)))
            .put("outbounds", JSONArray().put(outbound(node, serverAddress)))
            .put("route", route).toString()
    }

    private fun outbound(node: ProxyNode, serverAddress: String = node.host): JSONObject {
        val options = node.options
        val out = JSONObject().put("type", node.protocol).put("tag", "node").put("server", serverAddress).put("server_port", node.port)
        val security = options["security"].orEmpty()
        val tls = JSONObject().put("enabled", true).put("server_name", options["sni"]?.takeIf { it.isNotBlank() } ?: node.host)
        options["alpn"]?.takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONArray(it.split(','))) }
        // Do not disable certificate verification based on subscription flags.
        if (node.protocol == "hysteria2") {
            out.put("password", node.credential).put("tls", tls)
            options["obfs"]?.takeIf { it.isNotBlank() }?.let { obfs ->
                require(obfs == "salamander") { "Неподдерживаемый формат HY2." }
                val password = options["obfs-password"] ?: options["obfsPassword"]
                require(!password.isNullOrBlank()) { "У HY2 отсутствует пароль обфускации." }
                out.put("obfs", JSONObject().put("type", obfs).put("password", password))
            }
            options["pinSHA256"]?.takeIf { it.isNotBlank() }?.let { throw IllegalArgumentException("HY2 с пином пока не поддерживается.") }
        } else {
            require(options["encryption"].orEmpty() in setOf("", "none")) { "Неподдерживаемое шифрование VLESS." }
            out.put("uuid", node.credential).put("packet_encoding", "xudp")
            options["flow"]?.takeIf { it.isNotBlank() }?.let { out.put("flow", it) }
            require(security in setOf("", "none", "tls", "reality")) { "Неподдерживаемый режим TLS VLESS." }
            if (security == "reality") {
                val publicKey = options["pbk"]
                require(!publicKey.isNullOrBlank()) { "У REALITY отсутствует публичный ключ." }
                tls.put("reality", JSONObject().put("enabled", true).put("public_key", publicKey).put("short_id", options["sid"].orEmpty()))
            }
            if (security in setOf("tls", "reality")) {
                val fp = options["fp"]?.takeIf { it.isNotBlank() && it != "none" }
                if (fp != null || security == "reality") tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp ?: "chrome"))
                out.put("tls", tls)
            }
            val transportType = options["type"].orEmpty()
            val transport = JSONObject()
            when (transportType) {
                "", "tcp", "raw" -> require(options["headerType"].orEmpty() in setOf("", "none")) { "VLESS с HTTP-заголовком поверх TCP пока не поддерживается." }
                "ws" -> {
                    transport.put("type", "ws").put("path", options["path"] ?: "/")
                    options["host"]?.let { transport.put("headers", JSONObject().put("Host", it)) }
                }
                "grpc" -> transport.put("type", "grpc").put("service_name", options["serviceName"].orEmpty())
                "http", "h2" -> {
                    transport.put("type", "http").put("path", options["path"] ?: "/")
                    options["host"]?.let { transport.put("host", JSONArray(it.split(','))) }
                }
                "httpupgrade" -> transport.put("type", "httpupgrade").put("path", options["path"] ?: "/").put("host", options["host"].orEmpty())
                else -> throw IllegalArgumentException("Неподдерживаемый транспорт VLESS.")
            }
            if (transport.length() != 0) out.put("transport", transport)
        }
        return out
    }
}
