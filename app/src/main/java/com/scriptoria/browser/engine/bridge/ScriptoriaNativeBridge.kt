package com.scriptoria.browser.engine.bridge

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import com.scriptoria.browser.R
import com.scriptoria.browser.ScriptoriaApp
import com.scriptoria.browser.data.repository.GmStorageRepository
import com.scriptoria.browser.data.repository.ScriptRepository
import com.scriptoria.browser.engine.console.LogLevel
import com.scriptoria.browser.engine.console.UserscriptConsole
import com.scriptoria.browser.engine.network.DownloadService
import com.scriptoria.browser.engine.network.GmXhrHandler
import com.scriptoria.browser.engine.network.StreamDownloads
import android.util.Base64
import android.webkit.URLUtil
import android.widget.Toast
import com.scriptoria.browser.data.preferences.DownloadPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

class ScriptoriaNativeBridge(
    private val webViewRef: WeakReference<WebView>,
    private val tokenManager: CapabilityTokenManager,
    private val gmStorageRepository: GmStorageRepository,
    private val scriptRepository: ScriptRepository,
    private val downloadPreferences: DownloadPreferences,
    private val xhrHandler: GmXhrHandler,
    private val onOpenTab: (url: String, active: Boolean) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeMenuCommands = ConcurrentHashMap<String, String>() // fnId -> caption

    private fun getAuthorizedScriptId(token: String?): Long? {
        val webView = webViewRef.get() ?: return null
        val currentUrl = webView.url.orEmpty()
        return tokenManager.getScriptIdIfValid(token, currentUrl)
    }

    @JavascriptInterface
    fun gmGetValue(token: String, key: String): String? {
        val scriptId = getAuthorizedScriptId(token) ?: return null
        return gmStorageRepository.getValue(scriptId, key)
    }

    @JavascriptInterface
    fun gmSetValue(token: String, key: String, valueJson: String) {
        val scriptId = getAuthorizedScriptId(token) ?: return
        gmStorageRepository.setValue(scriptId, key, valueJson)
    }

    @JavascriptInterface
    fun gmDeleteValue(token: String, key: String) {
        val scriptId = getAuthorizedScriptId(token) ?: return
        gmStorageRepository.deleteValue(scriptId, key)
    }

    @JavascriptInterface
    fun gmListValues(token: String): String {
        val scriptId = getAuthorizedScriptId(token) ?: return "[]"
        val keys = gmStorageRepository.listKeys(scriptId)
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        return arr.toString()
    }

    @JavascriptInterface
    fun gmXhr(token: String, reqId: String, detailsJson: String) {
        val scriptId = getAuthorizedScriptId(token)
        if (scriptId == null) {
            deliverXhrEvent(reqId, "error", JSONObject().put("error", "Unauthorized capability token").toString())
            return
        }

        xhrHandler.execute(reqId, detailsJson) { event, payloadJson ->
            deliverXhrEvent(reqId, event, payloadJson)
        }
    }

    @JavascriptInterface
    fun gmAbortXhr(token: String, reqId: String) {
        if (getAuthorizedScriptId(token) != null) {
            xhrHandler.abort(reqId)
        }
    }

    @JavascriptInterface
    fun gmNotification(token: String, title: String, text: String) {
        val scriptId = getAuthorizedScriptId(token) ?: return
        val context = webViewRef.get()?.context ?: return

        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(context, ScriptoriaApp.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()

            notificationManager.notify((scriptId * 1000 + (System.currentTimeMillis() % 1000)).toInt(), notification)
        } catch (e: Exception) {
            Log.e("ScriptoriaBridge", "Failed to post notification", e)
        }
    }

    @JavascriptInterface
    fun gmSetClipboard(token: String, text: String) {
        if (getAuthorizedScriptId(token) == null) return
        val context = webViewRef.get()?.context ?: return

        mainHandler.post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Userscript Clipboard", text)
                clipboard.setPrimaryClip(clip)
            } catch (e: Exception) {
                Log.e("ScriptoriaBridge", "Failed to set clipboard", e)
            }
        }
    }

    @JavascriptInterface
    fun gmOpenInTab(token: String, url: String, active: Boolean) {
        if (getAuthorizedScriptId(token) == null) return
        mainHandler.post {
            onOpenTab(url, active)
        }
    }

    @JavascriptInterface
    fun gmLog(token: String, message: String) {
        val scriptId = getAuthorizedScriptId(token)
        UserscriptConsole.addLog(
            level = LogLevel.LOG,
            scriptId = scriptId,
            scriptName = "Script #$scriptId",
            message = message
        )
    }

    @JavascriptInterface
    fun gmRegisterMenuCommand(token: String, caption: String, fnId: String) {
        if (getAuthorizedScriptId(token) == null) return
        activeMenuCommands[fnId] = caption
    }

    @JavascriptInterface
    fun gmUnregisterMenuCommand(token: String, fnId: String) {
        if (getAuthorizedScriptId(token) == null) return
        activeMenuCommands.remove(fnId)
    }

    /**
     * Streaming download, for media only the page can fetch (Telegram's blob: and
     * service-worker URLs have no origin a native request could reach).
     *
     * The script calls this on click, then feeds chunks — so the file shows up in the Downloads
     * screen straight away and progresses as it transfers, instead of the page buffering the
     * whole thing and handing over one enormous base64 string at the end.
     *
     * Returns a session id, or -1 if the destination could not be opened.
     */
    @JavascriptInterface
    fun beginStreamDownload(fileName: String, mimeType: String?, totalBytes: String?): Int {
        val context = webViewRef.get()?.context?.applicationContext ?: return -1
        // Passed as a string: JS numbers past 2^31 do not survive the int bridge.
        val total = totalBytes?.toLongOrNull() ?: -1L
        return StreamDownloads.begin(context, fileName, mimeType, total)
    }

    /**
     * Appends one base64 chunk. Returns false once the session is gone (cancelled from the
     * Downloads screen, or a write failed), which is the script's signal to stop fetching.
     */
    @JavascriptInterface
    fun writeStreamChunk(id: Int, base64Chunk: String): Boolean {
        val context = webViewRef.get()?.context?.applicationContext ?: return false
        return try {
            StreamDownloads.write(context, id, Base64.decode(base64Chunk, Base64.DEFAULT))
        } catch (e: IllegalArgumentException) {
            Log.e("ScriptoriaBridge", "Malformed chunk for stream #$id", e)
            StreamDownloads.abort(context, id)
            false
        }
    }

    /** Supplies the total once the page reads it from a Content-Range header. */
    @JavascriptInterface
    fun setStreamDownloadTotal(id: Int, totalBytes: String?) {
        val context = webViewRef.get()?.context?.applicationContext ?: return
        StreamDownloads.setTotal(context, id, totalBytes?.toLongOrNull() ?: return)
    }

    /** Lets the page report where a transfer spent its time, so it reaches logcat. */
    @JavascriptInterface
    fun logDownloadStats(id: Int, stats: String) {
        Log.i("StreamDownloads", "stats #$id $stats")
    }

    @JavascriptInterface
    fun finishStreamDownload(id: Int) {
        val context = webViewRef.get()?.context?.applicationContext ?: return
        StreamDownloads.finish(context, id)
    }

    @JavascriptInterface
    fun abortStreamDownload(id: Int) {
        val context = webViewRef.get()?.context?.applicationContext ?: return
        StreamDownloads.abort(context, id)
    }

    /** Lets a long-running script notice the user cancelled without writing another chunk. */
    @JavascriptInterface
    fun isStreamDownloadActive(id: Int): Boolean = StreamDownloads.isActive(id)

    @JavascriptInterface
    fun saveBlobDownload(fileName: String, base64Data: String, mimeType: String?) {
        val context = webViewRef.get()?.context ?: return
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "telegram_download.mp4" }
        val effectiveMime = mimeType?.ifEmpty { null } ?: guessMimeType(safeName)
        val folderName = downloadPreferences.folderDisplayName

        mainHandler.post {
            Toast.makeText(context, "Saving $safeName to $folderName...", Toast.LENGTH_SHORT).show()
        }

        Thread {
            var sink: com.scriptoria.browser.data.repository.DownloadSink? = null
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)

                // Same destination resolution as streamed downloads (custom SAF folder,
                // else Downloads/Scriptoria), so both paths honour the user's setting.
                sink = com.scriptoria.browser.data.repository.DownloadSink.create(
                    context = context,
                    preferences = downloadPreferences,
                    requestedName = safeName,
                    mimeType = effectiveMime
                )
                sink.openOutputStream().use { os -> os.write(bytes) }
                sink.markComplete()

                val sizeDisplay = if (bytes.size >= 1048576) {
                    String.format(java.util.Locale.US, "%.1f MB", bytes.size / 1048576f)
                } else {
                    "${bytes.size / 1024} KB"
                }

                UserscriptConsole.addLog(
                    level = LogLevel.INFO,
                    scriptId = null,
                    scriptName = "Downloader",
                    message = "Saved $safeName ($sizeDisplay) to $folderName"
                )

                mainHandler.post {
                    Toast.makeText(context, "Saved $safeName to $folderName", Toast.LENGTH_LONG).show()
                }

                showDownloadNotification(context, safeName, bytes.size)
            } catch (oom: OutOfMemoryError) {
                sink?.discard()
                Log.e("ScriptoriaBridge", "Out of memory saving $safeName", oom)
                mainHandler.post {
                    Toast.makeText(context, "Insufficient memory to save $safeName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                sink?.discard()
                Log.e("ScriptoriaBridge", "Failed to save blob download: $safeName", e)
                mainHandler.post {
                    Toast.makeText(context, "Failed to save $safeName: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    @JavascriptInterface
    fun downloadUrl(url: String, fileName: String?, userAgent: String?, mimeType: String?) {
        val webView = webViewRef.get() ?: return
        val context = webView.context ?: return
        val safeName = fileName?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.ifEmpty { null }
            ?: URLUtil.guessFileName(url, null, mimeType)
        // Many media hosts reject requests without the originating page.
        val referer = webView.url

        try {
            // Deliberately not the system DownloadManager: streaming it ourselves keeps the
            // transfer on the app's own client and lets the Downloads screen show and cancel it.
            DownloadService.enqueue(
                context = context.applicationContext,
                url = url,
                fileName = safeName,
                mimeType = mimeType,
                userAgent = userAgent,
                referer = referer
            )
        } catch (e: Exception) {
            Log.e("ScriptoriaBridge", "Failed to enqueue download for $url", e)
            mainHandler.post {
                Toast.makeText(context, "Could not start download: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDownloadNotification(context: Context, fileName: String, sizeBytes: Int) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val sizeDisplay = if (sizeBytes >= 1048576) {
                String.format(java.util.Locale.US, "%.1f MB", sizeBytes / 1048576f)
            } else {
                "${sizeBytes / 1024} KB"
            }
            val notification = NotificationCompat.Builder(context, ScriptoriaApp.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Download Complete")
                .setContentText("$fileName ($sizeDisplay)")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .build()

            notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
        } catch (e: Exception) {
            Log.e("ScriptoriaBridge", "Failed to show download notification", e)
        }
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "zip" -> "application/zip"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun deliverXhrEvent(reqId: String, eventName: String, payloadJson: String) {
        mainHandler.post {
            val webView = webViewRef.get() ?: return@post
            val js = "window.__scriptoriaHub && window.__scriptoriaHub.handleXhrEvent(" +
                    "${JSONObject.quote(reqId)}, " +
                    "${JSONObject.quote(eventName)}, " +
                    "${JSONObject.quote(payloadJson)});"
            webView.evaluateJavascript(js, null)
        }
    }

    fun invokeMenuCommand(fnId: String) {
        mainHandler.post {
            val webView = webViewRef.get() ?: return@post
            val js = "window.__scriptoriaHub && window.__scriptoriaHub.invokeMenu && window.__scriptoriaHub.invokeMenu(${JSONObject.quote(fnId)});"
            webView.evaluateJavascript(js, null)
        }
    }
}
