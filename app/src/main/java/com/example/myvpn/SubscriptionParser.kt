package com.example.myvpn

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal class ProxyNode(
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val credential: String,
    val options: Map<String, String>,
    val link: String,
) {
    override fun toString(): String = "ProxyNode(protocol=$protocol, host=$host, port=$port)"
}

internal object SubscriptionParser {
    enum class Format { EMPTY, HTML, JSON, LINKS, BASE64, UNKNOWN }
    class Result(val format: Format, val nodes: List<ProxyNode>)

    fun parse(body: String): List<ProxyNode> = inspect(body).nodes

    fun inspect(body: String): Result {
        val text = clean(body)
        return when (formatOf(text)) {
            Format.EMPTY -> Result(Format.EMPTY, emptyList())
            Format.HTML -> Result(Format.HTML, emptyList())
            Format.JSON -> Result(Format.JSON, XraySubscription.links(text).mapNotNull(::parseLink).distinctBy { it.link })
            Format.LINKS -> Result(Format.LINKS, parseLinks(text))
            else -> {
                val decoded = clean(decodeList(text))
                when (formatOf(decoded)) {
                    Format.JSON -> Result(Format.JSON, XraySubscription.links(decoded).mapNotNull(::parseLink).distinctBy { it.link })
                    Format.LINKS -> Result(Format.BASE64, parseLinks(decoded))
                    Format.HTML -> Result(Format.HTML, emptyList())
                    else -> Result(Format.UNKNOWN, emptyList())
                }
            }
        }
    }

    private fun clean(text: String): String = text.trim().trimStart('\uFEFF').trim()

    private fun formatOf(text: String): Format = when {
        text.isBlank() -> Format.EMPTY
        text.startsWith('<') -> Format.HTML
        text.startsWith('{') || text.startsWith('[') -> Format.JSON
        text.lineSequence().any { val line = it.trimStart(); !line.startsWith('#') && "://" in line } -> Format.LINKS
        else -> Format.UNKNOWN
    }

    private fun parseLinks(text: String): List<ProxyNode> = text.lineSequence().map(String::trim)
        .filterNot { it.startsWith('#') }.distinct().mapNotNull(::parseLink).toList()

    private fun decodeList(text: String): String {
        val compact = text.filterNot(Char::isWhitespace)
        return sequenceOf(Base64.getDecoder(), Base64.getUrlDecoder()).firstNotNullOfOrNull { decoder ->
            runCatching { String(decoder.decode(compact), Charsets.UTF_8).trimStart('\uFEFF') }.getOrNull()
        }.orEmpty()
    }

    private fun parseLink(link: String): ProxyNode? = runCatching {
        val uri = URI(link.substringBefore('#'))
        val fragment = link.substringAfter('#', "")
        val name = runCatching { decode(fragment) }.getOrDefault(fragment)
        val protocol = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "vless" -> "vless"
            "hysteria2", "hy2" -> "hysteria2"
            else -> return null
        }
        val host = uri.host?.removeSurrounding("[", "]") ?: return null
        val port = if (uri.port == -1 && protocol == "hysteria2") 443 else uri.port
        if (port !in 1..65535) return null
        val credential = uri.userInfo?.takeIf(String::isNotBlank) ?: return null
        if (protocol == "vless" && !UUID.fromString(credential).toString().equals(credential, ignoreCase = true)) return null
        val options = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { option ->
            decode(option.substringBefore('=')) to decode(option.substringAfter('=', ""))
        }
        ProxyNode(name.takeIf(String::isNotBlank) ?: host, protocol, host, port, credential, options, link)
    }.getOrNull()

    private fun decode(text: String): String = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
}
