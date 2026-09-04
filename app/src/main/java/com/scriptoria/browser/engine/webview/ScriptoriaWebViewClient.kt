package com.scriptoria.browser.engine.webview

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.scriptoria.browser.data.repository.InstalledScript
import com.scriptoria.browser.engine.console.LogLevel
import com.scriptoria.browser.engine.console.UserscriptConsole
import com.scriptoria.browser.engine.executor.UserscriptManager
import com.scriptoria.browser.engine.parser.RunAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScriptoriaWebViewClient(
    private val userscriptManager: UserscriptManager,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val onUrlChange: (url: String) -> Unit,
    private val onUserscriptUrlDetected: (url: String) -> Unit,
    private val onScriptsActiveCountChanged: (count: Int, scripts: List<InstalledScript>) -> Unit
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false

        // Intercept userscript installation URLs (.user.js)
        val cleanUrl = url.substringBefore('?').substringBefore('#')
        if (cleanUrl.endsWith(".user.js")) {
            onUserscriptUrlDetected(url)
            return true
        }

        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url == null || view !is ScriptoriaWebView) return

        onUrlChange(url)
        view.tokenManager.clearForNavigation()

        // Inject early runtime polyfills (download hook, FileSystemAccess API, blob protection)
        view.evaluateJavascript(CORE_DOWNLOAD_POLYFILL, null)

        // Inject document-start scripts as early as possible
        injectScripts(view, url, RunAt.DOCUMENT_START)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url == null || view !is ScriptoriaWebView) return

        // Inject document-body and document-end scripts
        injectScripts(view, url, RunAt.DOCUMENT_BODY)
        injectScripts(view, url, RunAt.DOCUMENT_END)
        injectScripts(view, url, RunAt.DOCUMENT_IDLE)

        // Notify UI about total active scripts on this page
        val active = userscriptManager.getActiveScriptsForUrl(url)
        onScriptsActiveCountChanged(active.size, active)
    }

    private fun injectScripts(webView: ScriptoriaWebView, url: String, runAt: RunAt) {
        val matching = userscriptManager.getMatchingScripts(url, runAt)
        if (matching.isEmpty()) return

        coroutineScope.launch {
            for (script in matching) {
                try {
                    val token = webView.tokenManager.mintToken(script.id, url)
                    val bundleJs = userscriptManager.buildInjectionBundle(script, token)

                    webView.evaluateJavascript(bundleJs) { result ->
                        UserscriptConsole.addLog(
                            level = LogLevel.INFO,
                            scriptId = script.id,
                            scriptName = script.metadata.name,
                            message = "Executed successfully at ${runAt.tagValue}"
                        )
                    }
                    userscriptManager.scriptRepository.updateLastExecuted(script.id)
                } catch (e: Exception) {
                    Log.e("ScriptoriaClient", "Failed injecting ${script.metadata.name}", e)
                    UserscriptConsole.addLog(
                        level = LogLevel.ERROR,
                        scriptId = script.id,
                        scriptName = script.metadata.name,
                        message = "Injection failure: ${e.message}"
                    )
                }
            }
        }
    }

    companion object {
        private const val CORE_DOWNLOAD_POLYFILL = """
(function() {
    if (window.__scriptoriaCoreInjected) return;
    window.__scriptoriaCoreInjected = true;

    if (!window.showSaveFilePicker && window.ScriptoriaNativeBridge) {
        // Mapped onto the streaming bridge rather than buffering. Scripts written for desktop
        // (e.g. Telegram downloaders) call write() once per network chunk precisely so the file
        // never sits in memory; accumulating here and handing over one base64 string at close()
        // would defeat that and OOM on a large video.
        window.showSaveFilePicker = async function(options) {
            var suggestedName = (options && options.suggestedName) || 'download.mp4';
            var bridge = window.ScriptoriaNativeBridge;

            var toBlob = function(data) {
                if (data instanceof Blob) return data;
                if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) return new Blob([data]);
                if (typeof data === 'string') return new Blob([data]);
                return null;
            };

            var toBase64 = function(blob) {
                return new Promise(function(resolve, reject) {
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        var result = reader.result || '';
                        resolve(result.slice(result.indexOf(',') + 1));
                    };
                    reader.onerror = function() { reject(reader.error); };
                    reader.readAsDataURL(blob);
                });
            };

            return {
                createWritable: async function() {
                    var id = bridge.beginStreamDownload(suggestedName, '', '-1');
                    var broken = id < 0;
                    var SLICE = 4 * 1024 * 1024;

                    return {
                        write: async function(data) {
                            if (broken) return;
                            // The spec also allows { type, data, position } command objects.
                            var payload = data;
                            if (data && data.type && 'data' in data) {
                                if (data.type !== 'write') return;   // seek/truncate: not seekable
                                payload = data.data;
                            }
                            var blob = toBlob(payload);
                            if (!blob || blob.size === 0) return;
                            for (var pos = 0; pos < blob.size; pos += SLICE) {
                                var part = blob.slice(pos, Math.min(pos + SLICE, blob.size));
                                var chunk = await toBase64(part);
                                if (!bridge.writeStreamChunk(id, chunk)) { broken = true; return; }
                            }
                        },
                        close: async function() {
                            if (broken) { bridge.abortStreamDownload(id); return; }
                            bridge.finishStreamDownload(id);
                        },
                        abort: async function() { bridge.abortStreamDownload(id); }
                    };
                }
            };
        };
    }

    if (!window.__scriptoriaDownloadHookInstalled) {
        window.__scriptoriaDownloadHookInstalled = true;
        var origAnchorClick = HTMLAnchorElement.prototype.click;
        HTMLAnchorElement.prototype.click = function() {
            var href = this.href || this.getAttribute('href');
            var downloadAttr = this.download !== undefined && this.download !== null ? this.download : this.getAttribute('download');
            if (downloadAttr !== null && downloadAttr !== undefined && href && window.ScriptoriaNativeBridge) {
                var fileName = downloadAttr || 'download';
                if (href.startsWith('blob:') || href.startsWith('data:')) {
                    fetch(href)
                        .then(function(r) { return r.blob(); })
                        .then(function(blob) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var base64 = (reader.result || '').split(',')[1];
                                if (base64) {
                                    var mime = blob.type || 'application/octet-stream';
                                    window.ScriptoriaNativeBridge.saveBlobDownload(fileName, base64, mime);
                                }
                            };
                            reader.readAsDataURL(blob);
                        })
                        .catch(function(err) {
                            console.error('[Scriptoria] Failed to read blob for download:', err);
                        });
                    return;
                } else if (href.startsWith('http://') || href.startsWith('https://')) {
                    window.ScriptoriaNativeBridge.downloadUrl(href, fileName, navigator.userAgent, '');
                    return;
                }
            }
            return origAnchorClick.apply(this, arguments);
        };
    }

    if (!window.__scriptoriaRevokeHookInstalled) {
        window.__scriptoriaRevokeHookInstalled = true;
        var origRevoke = URL.revokeObjectURL;
        URL.revokeObjectURL = function(url) {
            setTimeout(function() {
                try { origRevoke(url); } catch (e) {}
            }, 60000);
        };
    }
})();
        """
    }
}
