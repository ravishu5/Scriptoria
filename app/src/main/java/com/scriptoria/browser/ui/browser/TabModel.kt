package com.scriptoria.browser.ui.browser

import com.scriptoria.browser.data.repository.InstalledScript
import com.scriptoria.browser.engine.webview.ScriptoriaWebView
import java.util.UUID

data class TabModel(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "https://duckduckgo.com",
    val title: String = "New Tab",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val activeScriptsCount: Int = 0,
    val activeScripts: List<InstalledScript> = emptyList(),
    var webView: ScriptoriaWebView? = null
)
