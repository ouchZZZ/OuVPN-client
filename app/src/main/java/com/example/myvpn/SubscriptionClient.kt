package com.example.myvpn

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

internal class SubscriptionFailure(message: String) : IOException(message)

internal object SubscriptionClient {
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun requireHttps(link: String): URI {
        val uri = runCatching { URI(link.trim()) }.getOrNull()
        require(uri?.scheme.equals("https", ignoreCase = true) && uri?.host != null && uri.userInfo == null && uri.fragment == null) {
            "Нужна HTTPS-ссылка на подписку."
        }
        return checkNotNull(uri)
    }

    fun headers(deviceId: String?, osVersion: String, model: String): Map<String, String> = buildMap {
        put("User-Agent", "Happ/4.10.1/Android")
        put("Accept-Encoding", "gzip")
        if (!deviceId.isNullOrBlank()) {
            put("X-HWID", deviceId)
            put("X-Device-OS", "Android")
            put("X-Ver-OS", osVersion)
            put("X-Device-Model", model)
        }
    }

    fun fetch(link: String, deviceId: String?, osVersion: String, model: String): List<ProxyNode> {
        val initial = requireHttps(link)
        val body = try {
            download(initial, deviceId, osVersion, model)
        } catch (error: SubscriptionFailure) {
            throw error
        } catch (_: UnknownHostException) {
            throw SubscriptionFailure("Загрузка подписки: DNS не смог найти сервер.")
        } catch (_: SSLException) {
            throw SubscriptionFailure("Загрузка подписки: не удалось установить защищённое HTTPS-соединение.")
        } catch (_: SocketTimeoutException) {
            throw SubscriptionFailure("Загрузка подписки: сервер не ответил вовремя.")
        } catch (_: IOException) {
            throw SubscriptionFailure("Загрузка подписки: не удалось получить или распаковать ответ сервера.")
        }
        return parseResponse(body)
    }

    internal fun parseResponse(body: String): List<ProxyNode> {
        val result = SubscriptionParser.inspect(body)
        if (result.nodes.isNotEmpty()) return result.nodes
        val reason = when (result.format) {
            SubscriptionParser.Format.EMPTY -> "пустой ответ (HTTP 200)."
            SubscriptionParser.Format.HTML -> "получен HTML (HTTP 200), а не список нод."
            SubscriptionParser.Format.JSON -> "получен JSON (HTTP 200), но в нём нет поддерживаемых профилей VLESS/HY2."
            SubscriptionParser.Format.LINKS, SubscriptionParser.Format.BASE64 -> "получен список (HTTP 200), но в нём нет корректных ссылок VLESS/HY2."
            SubscriptionParser.Format.UNKNOWN -> "неизвестный формат ответа (HTTP 200)."
        }
        throw SubscriptionFailure("Разбор подписки: $reason")
    }

    private fun download(initial: URI, deviceId: String?, osVersion: String, model: String): String {
        var uri = initial
        repeat(6) {
            val sameOrigin = initial.host.equals(uri.host, true) && portOf(initial) == portOf(uri)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                headers(deviceId.takeIf { sameOrigin }, osVersion, model).forEach(connection::setRequestProperty)
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val target = connection.getHeaderField("Location")
                        ?: throw SubscriptionFailure("Загрузка подписки: сервер не указал адрес перенаправления.")
                    uri = try {
                        requireHttps(uri.resolve(target).toString())
                    } catch (_: IllegalArgumentException) {
                        throw SubscriptionFailure("Загрузка подписки: сервер указал недопустимое перенаправление.")
                    }
                    return@repeat
                }
                if (status != 200) throw SubscriptionFailure("Загрузка подписки: сервер вернул HTTP $status.")
                return connection.inputStream.use { input ->
                    val source = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(input) else input
                    source.use {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = it.read(buffer)
                            if (size < 0) break
                            if (output.size() + size > MAX_BYTES) throw SubscriptionFailure("Загрузка подписки: ответ превышает 2 МиБ.")
                            output.write(buffer, 0, size)
                        }
                        String(output.toByteArray(), Charsets.UTF_8)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw SubscriptionFailure("Загрузка подписки: слишком много перенаправлений.")
    }

    private fun portOf(uri: URI): Int = uri.port.takeIf { it > 0 } ?: 443
}
