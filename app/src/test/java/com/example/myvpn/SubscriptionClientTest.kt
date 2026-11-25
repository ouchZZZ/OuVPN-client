package com.example.myvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SubscriptionClientTest {
    @Test fun happProfileDoesNotSendDeviceIdUnlessRequested() {
        val headers = SubscriptionClient.headers(null, "16", "Example")
        assertEquals("Happ/4.10.1/Android", headers["User-Agent"])
        assertFalse(headers.containsKey("X-HWID"))
    }

    @Test fun deviceProfileSendsStableIdAndAndroidMetadata() {
        val headers = SubscriptionClient.headers("test-id", "16", "Example")
        assertEquals("test-id", headers["X-HWID"])
        assertEquals("Android", headers["X-Device-OS"])
        assertEquals("16", headers["X-Ver-OS"])
        assertEquals("Example", headers["X-Device-Model"])
    }

    @Test fun acceptsHttpsOnlyAndDoesNotIncludeUrlInErrors() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SubscriptionClient.requireHttps("http://example.test/private-token")
        }
        assertFalse(error.message.orEmpty().contains("private-token"))
        assertEquals("example.test", SubscriptionClient.requireHttps("https://example.test/private-token").host)
    }
}
