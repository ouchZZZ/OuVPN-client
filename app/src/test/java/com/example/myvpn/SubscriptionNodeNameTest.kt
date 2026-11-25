package com.example.myvpn

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionNodeNameTest {
    @Test fun prefixesDisplayNameWithoutChangingConnectionIdentity() {
        val link = "vless://00000000-0000-0000-0000-000000000001@edge.example:443#Test%20node"
        val original = SubscriptionParser.parse(link).single()
        val named = SavedSubscription("Example", "https://example.test/subscription", false, listOf(original)).namedNodes().single()
        assertEquals("Example | Test node", named.name)
        assertEquals("Test node", original.name)
        assertEquals(original.link, named.link)
        assertEquals(original.credential, named.credential)
        assertEquals(original.options, named.options)
        assertEquals("через Example | Test node", HomeRoute(named).label)
    }
}
