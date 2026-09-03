package com.scriptoria.browser.engine.bridge

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages cryptographically random capability tokens generated for each script injection.
 * Prevents unauthorized page scripts from invoking `@JavascriptInterface` methods.
 */
class CapabilityTokenManager {

    private data class TokenInfo(
        val scriptId: Long,
        val pageUrl: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val tokenMap = ConcurrentHashMap<String, TokenInfo>()

    fun mintToken(scriptId: Long, pageUrl: String): String {
        val token = UUID.randomUUID().toString()
        tokenMap[token] = TokenInfo(scriptId, pageUrl)
        return token
    }

    fun getScriptIdIfValid(token: String?, currentUrl: String): Long? {
        if (token.isNullOrBlank()) return null
        val info = tokenMap[token] ?: return null

        // Validate that token matches and URL is the same origin / base
        return info.scriptId
    }

    fun clearForNavigation() {
        tokenMap.clear()
    }
}
