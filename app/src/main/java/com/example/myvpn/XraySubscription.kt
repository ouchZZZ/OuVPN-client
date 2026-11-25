package com.example.myvpn

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Translate supported endpoint fields only. Never run provider-supplied Xray configurations. */
internal object XraySubscription {
    fun links(text: String): List<String> {
        val root = runCatching { JSONTokener(text).nextValue() }.getOrNull()
        return when (root) {
            is JSONArray -> root.items().flatMap(::convert).toList()
            is JSONObject -> convert(root).toList()
            else -> emptyList()
        }
    }

    private fun convert(profile: JSONObject): Sequence<String> = sequence {
        val name = profile.text("remarks")
        for (out in profile.optJSONArray("outbounds").items()) {
            val settings = out.optJSONObject("settings") ?: continue
            val stream = out.optJSONObject("streamSettings") ?: JSONObject()
            if (out.optJSONObject("proxySettings")?.text("tag") != null || stream.optJSONObject("sockopt")?.text("dialerProxy") != null) continue
            if ((stream.optJSONObject("finalmask")?.length() ?: 0) != 0 || (stream.optJSONObject("finalMask")?.length() ?: 0) != 0) continue
            val options = transportOptions(stream)
            when (out.text("protocol")) {
                "vless" -> {
                    val servers = settings.optJSONArray("vnext")
                    if (servers == null) {
                        makeLink("vless", settings, settings.text("id"), name, options + userOptions(settings))?.let { yield(it) }
                    } else for (server in servers.items()) {
                        for (user in server.optJSONArray("users").items()) {
                            makeLink("vless", server, user.text("id"), name, options + userOptions(user))?.let { yield(it) }
                        }
                    }
                }
                "hysteria" -> {
                    val hy = stream.optJSONObject("hysteriaSettings") ?: continue
                    if (settings.optInt("version", -1) != 2 || hy.optInt("version", 2) != 2) continue
                    if (options["security"] != "tls" || options["type"].let { it != null && it != "hysteria" }) continue
                    makeLink("hysteria2", settings, hy.text("auth"), name, options)?.let { yield(it) }
                }
            }
        }
    }

    private fun userOptions(user: JSONObject): Map<String, String> = buildMap {
        user.text("flow")?.let { put("flow", it) }
        user.text("encryption")?.let { put("encryption", it) }
    }

    private fun transportOptions(stream: JSONObject): Map<String, String> = buildMap {
        val security = stream.text("security") ?: "none"
        put("security", security)
        val method = when (val raw = stream.text("network") ?: stream.text("method")) {
            "websocket" -> "ws"
            else -> raw
        }
        method?.let { put("type", it) }
        val tls = stream.optJSONObject(if (security == "reality") "realitySettings" else "tlsSettings")
        tls?.text("serverName")?.let { put("sni", it) }
        tls?.text("fingerprint")?.let { put("fp", it) }
        tls?.optJSONArray("alpn")?.let { list ->
            val protocols = (0 until list.length()).mapNotNull { list.opt(it) as? String }
            if (protocols.isNotEmpty()) put("alpn", protocols.joinToString(","))
        }
        if (security == "reality") {
            (tls?.text("password") ?: tls?.text("publicKey"))?.let { put("pbk", it) }
            tls?.text("shortId")?.let { put("sid", it) }
        }
        when (method) {
            "raw", "tcp", null -> (stream.optJSONObject("rawSettings") ?: stream.optJSONObject("tcpSettings"))
                ?.optJSONObject("header")?.text("type")?.let { put("headerType", it) }
            "ws", "httpupgrade", "http", "h2" -> {
                val settings = stream.optJSONObject("${if (method == "h2") "http" else method}Settings")
                settings?.text("path")?.let { put("path", it) }
                val headers = settings?.optJSONObject("headers")
                val hosts = settings?.optJSONArray("host")?.let { list -> (0 until list.length()).mapNotNull { list.opt(it) as? String }.joinToString(",") }
                (settings?.text("host") ?: hosts?.takeIf(String::isNotBlank) ?: headers?.text("Host") ?: headers?.text("host"))?.let { put("host", it) }
            }
            "grpc" -> stream.optJSONObject("grpcSettings")?.text("serviceName")?.let { put("serviceName", it) }
        }
    }

    private fun makeLink(scheme: String, server: JSONObject, credential: String?, name: String?, options: Map<String, String>): String? {
        val host = server.text("address")?.removeSurrounding("[", "]") ?: return null
        if (host.any { it.isWhitespace() || it in "/?#@[]" } || credential == null) return null
        val port = server.optInt("port", -1).takeIf { it in 1..65535 } ?: return null
        val authority = if (':' in host) "[$host]" else host
        val query = options.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return "$scheme://${encode(credential)}@$authority:$port?$query#${encode(name ?: host)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf(String::isNotBlank)
    private fun JSONArray?.items(): Sequence<JSONObject> = if (this == null) emptySequence()
        else (0 until length()).asSequence().mapNotNull { optJSONObject(it) }
}
