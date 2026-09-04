package com.scriptoria.browser.engine.adblock

import android.net.Uri
import android.webkit.JavascriptInterface

/**
 * Lets the document-start script ask for the hiding rules that apply to the document it is running
 * in, which is the only way to get them host-correct: the native side cannot know the final URL
 * ahead of a redirect, but the page can always report its own.
 *
 * Deliberately separate from ScriptoriaNativeBridge — that object is gated on per-script capability
 * tokens, and nothing here needs to be. The only thing exposed is which selectors a public filter
 * list hides.
 */
class AdblockJsBridge(private val adblockManager: AdblockManager) {

    @JavascriptInterface
    fun getCosmeticCss(url: String?): String {
        val parsed = pageUri(url) ?: return ""
        return adblockManager.cosmeticCss(parsed) ?: ""
    }

    /** The scriptlet calls for this document, as a JSON array of [name, arg, ...] arrays. */
    @JavascriptInterface
    fun getScriptletCalls(url: String?): String {
        val parsed = pageUri(url) ?: return "[]"
        return adblockManager.scriptletCalls(parsed)
    }

    private fun pageUri(url: String?): Uri? {
        val parsed = runCatching { Uri.parse(url ?: return null) }.getOrNull() ?: return null
        return parsed.takeIf { it.scheme == "http" || it.scheme == "https" }
    }

    companion object {
        const val NAME = "ScriptoriaAdblock"

        /**
         * Runs before the page's own scripts, in every frame: the scriptlets have to patch window
         * ahead of the code they defuse, and the hiding rule has to be in the stylesheet by the
         * time the elements it targets are parsed.
         */
        const val INJECTION = """
(function() {
    if (window.__scriptoriaAdblockApplied) return;
    window.__scriptoriaAdblockApplied = true;
    var bridge = window.ScriptoriaAdblock;
    if (!bridge) return;
    var href = location.href;

    // Scriptlets first: they are only useful while the page's own scripts have yet to run.
    try {
        var calls = bridge.getScriptletCalls(href);
        if (calls && calls !== '[]' && window.__scriptoriaScriptlets) {
            window.__scriptoriaScriptlets(JSON.parse(calls));
        }
    } catch (e) {}

    try {
        var css = bridge.getCosmeticCss(href);
        if (css) {
            var style = document.createElement('style');
            style.setAttribute('data-scriptoria', 'adblock');
            style.textContent = css;
            (document.head || document.documentElement).appendChild(style);
        }
    } catch (e) {}
})();
"""
    }
}
