package com.example.myvpn

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HappImportTest {
    private val id = "00000000-0000-0000-0000-000000000001"

    @Test fun acceptsPlainNamesInUriFragments() {
        val link = "vless://$id@edge.example:443#🇳🇱 Node + 100%"
        assertEquals("🇳🇱 Node + 100%", SubscriptionParser.parse(link).single().name)
    }

    @Test fun importsXrayJsonVlessAndRoundTripsThroughCachedLinks() {
        val json = """[{"remarks":"Example NL","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"edge.example","port":443,"users":[{"id":"$id","flow":"xtls-rprx-vision","encryption":"none"}]}]},"streamSettings":{"method":"raw","security":"reality","realitySettings":{"serverName":"sni.example","password":"test-pubkey","shortId":"1234","fingerprint":"chrome"}}},{"protocol":"freedom"}]}]"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        val node = SubscriptionParser.parse(encoded).single()
        assertEquals("Example NL", node.name)
        assertEquals("test-pubkey", node.options["pbk"])
        assertEquals("sni.example", node.options["sni"])
        assertEquals("xtls-rprx-vision", node.options["flow"])
        assertEquals(node.options, SubscriptionParser.parse(node.link).single().options)
    }

    @Test fun importsXrayJsonHysteria2WithIpv6AndEscapedCredential() {
        val json = """{"remarks":"Example HY2","outbounds":[{"protocol":"hysteria","settings":{"version":2,"address":"2001:db8::1","port":443},"streamSettings":{"method":"hysteria","security":"tls","hysteriaSettings":{"version":2,"auth":"a:@#b"},"tlsSettings":{"serverName":"cert.example"}}}]}"""
        val node = SubscriptionParser.parse(json).single()
        assertEquals("hysteria2", node.protocol)
        assertEquals("2001:db8::1", node.host)
        assertEquals("a:@#b", node.credential)
        assertEquals("cert.example", node.options["sni"])
    }

    @Test fun reportsAnUnrecognizedSuccessfulResponseWithoutEchoingItsContents() {
        val error = assertThrows(SubscriptionFailure::class.java) {
            SubscriptionClient.parseResponse("<html>private-response-token</html>")
        }
        assertEquals("Разбор подписки: получен HTML (HTTP 200), а не список нод.", error.message)
        assertFalse(error.message.orEmpty().contains("private-response-token"))
    }
}
