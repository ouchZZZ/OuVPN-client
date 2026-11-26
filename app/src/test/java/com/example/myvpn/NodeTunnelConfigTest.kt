package com.example.myvpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NodeTunnelConfigTest {
    @Test fun unsupportedEncryptedProfilesAreNotEligibleForDirectTunnel() {
        val vless = "vless://00000000-0000-0000-0000-000000000003@edge.example:443"
        val unsupported = listOf(
            "$vless?security=tls&type=xhttp",
            "$vless?security=reality",
            "hysteria2://fixture@edge.example:443?obfs=salamander",
        )
        unsupported.forEach { assertFalse(SingBoxConfig.supportsNodeTunnel(SubscriptionParser.parse(it).single())) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPlaintextVlessInNodeOnlyMode() {
        val node = SubscriptionParser.parse("vless://00000000-0000-0000-0000-000000000003@edge.example:443?security=none").single()
        SingBoxConfig.nodeTunnel(node)
    }

    @Test fun payloadAndDnsHaveOnlyTheSelectedNodeAsEgress() {
        val node = SubscriptionParser.parse("vless://00000000-0000-0000-0000-000000000003@edge.example:443?security=tls").single()
        val cfg = JSONObject(SingBoxConfig.nodeTunnel(node))
        val inbound = cfg.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(1, cfg.getJSONArray("inbounds").length())
        assertEquals("tun", inbound.getString("type"))
        assertEquals("gvisor", inbound.getString("stack"))
        assertFalse(inbound.getBoolean("auto_route"))
        assertFalse(inbound.has("listen"))
        assertFalse(inbound.has("listen_port"))
        val route = cfg.getJSONObject("route")
        assertEquals("node", route.getString("final"))
        assertEquals("hijack-dns", route.getJSONArray("rules").getJSONObject(0).getString("action"))
        assertEquals("node", cfg.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("detour"))
        assertFalse(cfg.has("endpoints"))
        assertEquals(1, cfg.getJSONArray("outbounds").length())
    }

    @Test fun resolvedNodeAddressKeepsTheOriginalHostnameForTls() {
        val node = SubscriptionParser.parse("vless://00000000-0000-0000-0000-000000000003@edge.example:443?security=tls").single()
        val cfg = JSONObject(SingBoxConfig.nodeTunnel(node, serverAddress = "192.0.2.1"))
        val outbound = cfg.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("192.0.2.1", outbound.getString("server"))
        assertEquals("edge.example", outbound.getJSONObject("tls").getString("server_name"))
        assertEquals(1, cfg.getJSONObject("dns").getJSONArray("servers").length())
        assertFalse(cfg.getJSONObject("route").has("default_domain_resolver"))
    }

    @Test fun resolvedNodeAddressKeepsAnExplicitSni() {
        val node = SubscriptionParser.parse("hysteria2://fixture@edge.example:443?sni=tls.example").single()
        val cfg = JSONObject(SingBoxConfig.nodeTunnel(node, serverAddress = "2001:db8::1"))
        val outbound = cfg.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("2001:db8::1", outbound.getString("server"))
        assertEquals("tls.example", outbound.getJSONObject("tls").getString("server_name"))
    }
}
