package com.scriptoria.browser.engine.adblock

import com.scriptoria.browser.data.preferences.AdblockPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdblockAllowlistTest {

    @Test
    fun exactHostMatches() {
        assertTrue(AdblockPreferences.matches("example.com", setOf("example.com")))
    }

    @Test
    fun subdomainInheritsParentRule() {
        val list = setOf("example.com")
        assertTrue(AdblockPreferences.matches("cdn.example.com", list))
        assertTrue(AdblockPreferences.matches("a.b.c.example.com", list))
    }

    @Test
    fun parentIsNotAllowedBySubdomainRule() {
        // Turning blocking off for one subdomain must not disable it for the whole site.
        assertFalse(AdblockPreferences.matches("example.com", setOf("cdn.example.com")))
    }

    @Test
    fun siblingDomainsAreUnaffected() {
        val list = setOf("example.com")
        assertFalse(AdblockPreferences.matches("notexample.com", list))
        assertFalse(AdblockPreferences.matches("example.com.evil.net", list))
        assertFalse(AdblockPreferences.matches("other.org", list))
    }

    @Test
    fun emptyInputsAreNotAllowlisted() {
        assertFalse(AdblockPreferences.matches(null, setOf("example.com")))
        assertFalse(AdblockPreferences.matches("example.com", emptySet()))
    }

    @Test
    fun singleLabelHostIsHandled() {
        // Hosts with no dot must terminate the suffix walk rather than loop.
        assertFalse(AdblockPreferences.matches("localhost", setOf("example.com")))
        assertTrue(AdblockPreferences.matches("localhost", setOf("localhost")))
    }
}
