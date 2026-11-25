package com.example.myvpn

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionParserTest {
    private val vless = "vless://00000000-0000-0000-0000-000000000001@edge.example:443?type=ws&path=%2Fws&security=tls#Test%20VLESS"
    private val hy2 = "hy2://test%3Apassword@hy.example:8443?sni=hy.example#Test%20HY2"

    @Test fun parsesSupportedUriListAndIgnoresUnsupportedProtocols() {
        val nodes = SubscriptionParser.parse("#profile-title: test\n$vless\n$hy2\nss://unsupported")
        assertEquals(listOf("Test VLESS", "Test HY2"), nodes.map { it.name })
        assertEquals(listOf("vless", "hysteria2"), nodes.map { it.protocol })
        assertEquals("/ws", nodes.first().options["path"])
        assertEquals("test:password", nodes.last().credential)
        assertFalse(nodes.last().toString().contains("test:password"))
    }

    @Test fun parsesBase64AndDeduplicatesRepeatedNodes() {
        val body = Base64.getEncoder().encodeToString("$vless\n$vless\n$hy2".toByteArray())
        assertEquals(2, SubscriptionParser.parse(body).size)
    }

    @Test fun returnsNoNodesForHtmlOrMalformedSupportedEntries() {
        assertTrue(SubscriptionParser.parse("<html>\nvless://bad@edge.example:443\nhy2://@edge.example:443\n</html>").isEmpty())
    }
}
