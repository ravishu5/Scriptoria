package com.scriptoria.browser.engine.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import com.scriptoria.browser.ScriptoriaApp
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

        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
            nativeBridge.downloadUrl(url, fileName, userAgent, mimetype)
        }
    }

    /**
     * onPageStarted is delivered after the renderer has already begun running page scripts, so
     * the bridge cannot rely on it to know where it is. Recording the target here closes that
     * race for every load the app initiates, including the start page.
     */
    override fun loadUrl(url: String) {
        nativeBridge.updateCurrentUrl(url)
        super.loadUrl(url)
    }

    override fun destroy() {
        removeJavascriptInterface("ScriptoriaNativeBridge")
        tokenManager.clearForNavigation()
        super.destroy()
    }
}
