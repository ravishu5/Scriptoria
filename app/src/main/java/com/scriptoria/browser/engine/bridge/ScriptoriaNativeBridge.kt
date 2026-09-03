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
import com.scriptoria.browser.engine.network.GmXhrHandler
import android.app.DownloadManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.URLUtil
import android.widget.Toast
import java.io.File
import androidx.documentfile.provider.DocumentFile
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
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                var saved = false

                // 1. Check if user configured a custom SAF directory
                val customUriStr = downloadPreferences.customFolderUriString
                if (!customUriStr.isNullOrBlank()) {
                    try {
                        val treeUri = Uri.parse(customUriStr)
                        val docDir = DocumentFile.fromTreeUri(context, treeUri)
                        val targetFile = docDir?.createFile(effectiveMime, safeName)
                        if (targetFile != null) {
                            context.contentResolver.openOutputStream(targetFile.uri)?.use { os ->
                                os.write(bytes)
                            }
                            saved = true
                        }
                    } catch (e: Exception) {
                        Log.e("ScriptoriaBridge", "Failed writing to custom SAF folder, falling back", e)
                    }
                }

                // 2. Default fallback to public Downloads/Scriptoria
                if (!saved) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                            put(MediaStore.Downloads.MIME_TYPE, effectiveMime)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Scriptoria")
                        }
                        val resolver = context.contentResolver
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { os ->
                                os.write(bytes)
                            }
                            saved = true
                        }
                    } else {
                        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Scriptoria")
                        dir.mkdirs()
                        val dest = File(dir, safeName)
                        dest.writeBytes(bytes)
                        saved = true
                    }
                }

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
                Log.e("ScriptoriaBridge", "Out of memory saving $safeName", oom)
                mainHandler.post {
                    Toast.makeText(context, "Insufficient memory to save $safeName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ScriptoriaBridge", "Failed to save blob download: $safeName", e)
                mainHandler.post {
                    Toast.makeText(context, "Failed to save $safeName: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    @JavascriptInterface
    fun downloadUrl(url: String, fileName: String?, userAgent: String?, mimeType: String?) {
        val context = webViewRef.get()?.context ?: return
        val safeName = fileName?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.ifEmpty { null }
            ?: URLUtil.guessFileName(url, null, mimeType)

        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                setTitle(safeName)
                setDescription("Downloading via Scriptoria")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Scriptoria/$safeName")
            }
            dm.enqueue(request)

            mainHandler.post {
                Toast.makeText(context, "Downloading $safeName...", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ScriptoriaBridge", "Failed to enqueue download for $url", e)
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
