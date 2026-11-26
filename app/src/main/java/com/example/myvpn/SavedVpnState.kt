package com.example.myvpn

import android.content.Context
import com.wireguard.config.Config
import java.security.MessageDigest
import org.json.JSONObject

internal object SavedVpnState {
    private val lock = Any()

    fun loadConfig(context: Context): Config? = synchronized(lock) {
        configFile(context).read()?.let { Config.parse(it.byteInputStream(Charsets.UTF_8)) }
    }

    fun saveConfig(context: Context, config: Config) = synchronized(lock) {
        configFile(context).write(config.toWgQuickString())
    }

    fun loadReport(context: Context, config: Config?): RouteReport = synchronized(lock) {
        readReport(context, config)
    }

    fun saveReport(context: Context, config: Config?, latest: RouteReport): RouteReport = synchronized(lock) {
        val previous = runCatching { readReport(context, config) }.getOrDefault(emptyReport(config))
        val merged = RouteHistory.merge(previous, latest)
        historyFile(context, config).write(JSONObject().put("profile", profileId(config))
            .put("report", RouteHistory.encode(merged)).toString())
        merged
    }

    private fun readReport(context: Context, config: Config?): RouteReport {
        val encoded = historyFile(context, config).read() ?: return emptyReport(config)
        val json = JSONObject(encoded)
        if (json.getString("profile") != profileId(config)) return emptyReport(config)
        return RouteHistory.decode(json.getString("report")).copy(egress = mode(config))
    }

    private fun mode(config: Config?) = if (config == null) EgressMode.NODE else EgressMode.HOME
    private fun emptyReport(config: Config?) = RouteReport(egress = mode(config))
    private fun configFile(context: Context) = EncryptedFile(context, "wireguard.enc", "myvpn.wireguard.aes.v1")
    private fun historyFile(context: Context, config: Config?): EncryptedFile {
        val suffix = if (config == null) "node" else "home"
        return EncryptedFile(context, "speed-$suffix.enc", "myvpn.speed.$suffix.aes.v1")
    }

    private fun profileId(config: Config?) = if (config == null) "node" else android.util.Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(config.toWgQuickString().toByteArray(Charsets.UTF_8)),
        android.util.Base64.NO_WRAP,
    )
}
