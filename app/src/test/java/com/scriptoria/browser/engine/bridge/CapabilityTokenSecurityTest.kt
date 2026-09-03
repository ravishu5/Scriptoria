package com.scriptoria.browser.engine.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CapabilityTokenSecurityTest {

    private lateinit var tokenManager: CapabilityTokenManager

    @Before
    fun setUp() {
        tokenManager = CapabilityTokenManager()
    }

    @Test
    fun testValidTokenAuthorizesOnlyMatchingScript() {
        val scriptId = 42L
        val pageUrl = "https://example.com/home"

        val token = tokenManager.mintToken(scriptId, pageUrl)

        // Valid presentation returns scriptId
        val resolvedId = tokenManager.getScriptIdIfValid(token, pageUrl)
        assertEquals(scriptId, resolvedId)

        // Invalid or forged token returns null
        assertNull(tokenManager.getScriptIdIfValid("fake-token-123", pageUrl))
        assertNull(tokenManager.getScriptIdIfValid(null, pageUrl))
        assertNull(tokenManager.getScriptIdIfValid("", pageUrl))
    }

    @Test
    fun testNavigationClearsTokens() {
        val scriptId = 42L
        val pageUrl = "https://example.com/home"

        val token = tokenManager.mintToken(scriptId, pageUrl)
        assertEquals(scriptId, tokenManager.getScriptIdIfValid(token, pageUrl))

        // Navigation occurs
        tokenManager.clearForNavigation()

        // Old token is immediately dead
        assertNull(tokenManager.getScriptIdIfValid(token, pageUrl))
    }
}
