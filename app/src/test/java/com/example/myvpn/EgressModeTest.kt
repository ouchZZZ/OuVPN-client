package com.example.myvpn

import org.junit.Assert.assertEquals
import org.junit.Test

class EgressModeTest {
    @Test fun homeIsOnlySelectedWhenExplicitlyEnabledAndConfigured() {
        assertEquals(EgressMode.HOME, EgressMode.resolve(true, true))
        assertEquals(EgressMode.NODE, EgressMode.resolve(true, false))
        assertEquals(EgressMode.NODE, EgressMode.resolve(false, true))
        assertEquals(EgressMode.NODE, EgressMode.resolve(false, false))
    }
}
