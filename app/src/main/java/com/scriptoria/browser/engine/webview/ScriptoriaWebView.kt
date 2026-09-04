package com.scriptoria.browser.engine.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.scriptoria.browser.ScriptoriaApp
import com.scriptoria.browser.engine.adblock.AdblockJsBridge
import com.scriptoria.browser.engine.adblock.Scriptlets
import com.scriptoria.browser.engine.bridge.CapabilityTokenManager
import com.scriptoria.browser.engine.bridge.ScriptoriaNativeBridge
import com.scriptoria.browser.engine.network.GmXhrHandler
import java.lang.ref.WeakReference

@SuppressLint("SetJavaScriptEnabled")
class ScriptoriaWebView(
    context: Context,
    val tabId: String,
    private val onOpenNewTab: (url: String, active: Boolean) -> Unit
) : WebView(context) {

    val tokenManager = CapabilityTokenManager()
    val nativeBridge: ScriptoriaNativeBridge
    val adblockManager = (context.applicationContext as ScriptoriaApp).adblockManager

    /**
     * True when the cosmetic filter injection is registered to run before the page's own scripts.
     * Otherwise the client falls back to injecting it from onPageStarted, which is later but still
     * ahead of most of the document.
     */
    val hasDocumentStartInjection: Boolean

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        val app = context.applicationContext as ScriptoriaApp
        val xhrHandler = GmXhrHandler(app.httpClient)

        nativeBridge = ScriptoriaNativeBridge(
            webViewRef = WeakReference(this),
            tokenManager = tokenManager,
            gmStorageRepository = app.gmStorageRepository,
            scriptRepository = app.scriptRepository,
            downloadPreferences = app.downloadPreferences,
            xhrHandler = xhrHandler,
            onOpenTab = onOpenNewTab
        )

        addJavascriptInterface(nativeBridge, "ScriptoriaNativeBridge")
        addJavascriptInterface(AdblockJsBridge(adblockManager), AdblockJsBridge.NAME)

        hasDocumentStartInjection =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // Registered once for the life of the WebView: the script asks the bridge which
                // rules apply each time it runs, so it does not need re-registering per navigation.
                WebViewCompat.addDocumentStartJavaScript(this, documentStartScript(), setOf("*"))
                true
            } else {
                false
            }

        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
            nativeBridge.downloadUrl(url, fileName, userAgent, mimetype)
        }
    }

    /**
     * The scriptlet library ships whole and the bootstrap asks for only the arguments, so nothing
     * has to be evaluated from a string at run time.
     */
    fun documentStartScript(): String =
        Scriptlets.library(context) + "\n" + AdblockJsBridge.INJECTION

    /**
     * onPageStarted is delivered after the renderer has already begun running page scripts, so
     * the bridge cannot rely on it to know where it is. Recording the target here closes that
     * race for every load the app initiates, including the start page.
     */
    override fun loadUrl(url: String) {
        // shouldOverrideUrlLoading is not consulted for loads the app starts itself, so anything
        // typed in the omnibox or restored from a tab has to be cleaned here instead.
        val target = adblockManager.rewriteNavigation(url) ?: url
        nativeBridge.updateCurrentUrl(target)
        super.loadUrl(target)
    }

    override fun destroy() {
        removeJavascriptInterface("ScriptoriaNativeBridge")
        removeJavascriptInterface(AdblockJsBridge.NAME)
        tokenManager.clearForNavigation()
        super.destroy()
    }
}
