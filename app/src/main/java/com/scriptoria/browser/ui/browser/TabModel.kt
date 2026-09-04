package com.scriptoria.browser.ui.browser

import com.scriptoria.browser.data.repository.InstalledScript
import com.scriptoria.browser.engine.webview.ScriptoriaWebView
import java.util.UUID

/** Bundled start page: Google search plus shortcuts, served from assets so it works offline. */
const val HOME_URL = "file:///android_asset/home.html"

data class TabModel(
    val id: String = UUID.randomUUID().toString(),
    val url: String = HOME_URL,
    val title: String = "New Tab",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val activeScriptsCount: Int = 0,
    val activeScripts: List<InstalledScript> = emptyList(),
    var webView: ScriptoriaWebView? = null
)
