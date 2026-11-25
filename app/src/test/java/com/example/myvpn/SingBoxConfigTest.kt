package com.example.myvpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SingBoxConfigTest {
    @Test fun vlessUdpRelayHasNoDirectFallback() {
        val node = SubscriptionParser.parse("vless://00000000-0000-0000-0000-000000000001@edge.example:443?type=tcp&security=none").single()
        val config = JSONObject(SingBoxConfig.relay(node, 12345, "wg.example", 51820))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals("udp", inbound.getString("network"))
        assertEquals("wg.example", inbound.getString("override_address"))
        assertEquals(51820, inbound.getInt("override_port"))
        val outbounds = config.getJSONArray("outbounds")
        assertEquals(1, outbounds.length())
        assertEquals("xudp", outbounds.getJSONObject(0).getString("packet_encoding"))
        assertEquals("node", config.getJSONObject("route").getString("final"))
    }

    @Test fun hy2ProbeRejectsNonBenchmarkDestinations() {
        val node = SubscriptionParser.parse("hy2://test@edge.example:443?sni=cert.example").single()
        val config = JSONObject(SingBoxConfig.probe(node, 12345))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria2", outbound.getString("type"))
        assertEquals("cert.example", outbound.getJSONObject("tls").getString("server_name"))
        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("speed.cloudflare.com", rules.getJSONObject(0).getString("domain"))
        assertEquals("reject", rules.getJSONObject(1).getString("action"))
    }

    @Test fun unsupportedTransportIsRejectedRatherThanSilentlyRewritten() {
        val node = SubscriptionParser.parse("vless://00000000-0000-0000-0000-000000000001@edge.example:443?type=xhttp").single()
        assertThrows(IllegalArgumentException::class.java) { SingBoxConfig.probe(node, 12345) }
    }
}
