package com.scriptoria.browser.engine.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.scriptoria.browser.ScriptoriaApp
import com.scriptoria.browser.data.repository.DownloadManagerRepository
import com.scriptoria.browser.data.repository.DownloadSink
import com.scriptoria.browser.engine.console.LogLevel
import com.scriptoria.browser.engine.console.UserscriptConsole
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streams downloads through the app's own OkHttp client.
 *
 * This replaces Android's system DownloadManager, which runs out-of-process and therefore
 * cannot share the app's cookies, headers or connection state, reports progress only through
 * its own notifications, and gives the app no way to show or cancel a transfer in-app. Doing
 * the transfer ourselves puts downloads on the same client as GM_xmlhttpRequest and makes
 * their progress observable (see [ActiveDownloads]).
 *
 * It runs as a foreground service because the flagship use case is multi-hundred-megabyte
 * media files; a bare thread would be killed as soon as the user left the browser.
 */
class DownloadService : Service() {

    private val executor = Executors.newFixedThreadPool(MAX_PARALLEL)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeCalls = ConcurrentHashMap<Int, Call>()
    private val cancelled = ConcurrentHashMap<Int, Boolean>()
    private val activeCount = AtomicInteger(0)

    private val notifications by lazy { NotificationManagerCompat.from(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen within a few seconds of start, before any network work.
        startForeground(SUMMARY_NOTIFICATION_ID, buildSummaryNotification())

        // Host only: a full media URL often carries auth tokens, and logcat is the wrong
        // place for those.
        val host = intent?.getStringExtra(EXTRA_URL)?.toHttpUrlOrNull()?.host
        Log.i(TAG, "onStartCommand action=${intent?.action} host=$host")

        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getIntExtra(EXTRA_DOWNLOAD_ID, -1)
                if (id != -1) {
                    cancelled[id] = true
                    activeCalls[id]?.cancel()
                }
                stopIfIdle()
            }

            ACTION_ENQUEUE -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    stopIfIdle()
                    return START_NOT_STICKY
                }
                val id = ActiveDownloads.nextId()
                activeCount.incrementAndGet()
                refreshSummary()
                executor.execute {
                    try {
                        runDownload(
                            id = id,
                            url = url,
                            requestedName = intent.getStringExtra(EXTRA_FILENAME),
                            mimeHint = intent.getStringExtra(EXTRA_MIME),
                            userAgent = intent.getStringExtra(EXTRA_USER_AGENT),
                            referer = intent.getStringExtra(EXTRA_REFERER)
                        )
                    } finally {
                        activeCalls.remove(id)
                        cancelled.remove(id)
                        activeCount.decrementAndGet()
                        refreshSummary()
                        stopIfIdle()
                    }
                }
            }

            // A page-driven stream is running. Nothing to do but stay alive and foreground,
            // so the browser is not killed mid-transfer; StreamDownloads releases us later.
            ACTION_HOLD -> Unit

            ACTION_RELEASE -> stopIfIdle()

            else -> stopIfIdle()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeCalls.values.forEach { it.cancel() }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runDownload(
        id: Int,
        url: String,
        requestedName: String?,
        mimeHint: String?,
        userAgent: String?,
        referer: String?
    ) {
        var sink: DownloadSink? = null
        val fallbackName = DownloadSink.sanitizeFileName(
            requestedName?.takeIf { it.isNotBlank() } ?: guessNameFromUrl(url)
        )

        ActiveDownloads.start(id, fallbackName, url, mimeHint, userAgent, referer)

        try {
            val requestBuilder = Request.Builder().url(url)
            if (!userAgent.isNullOrBlank()) requestBuilder.header("User-Agent", userAgent)
            if (!referer.isNullOrBlank()) requestBuilder.header("Referer", referer)

            // The app-wide client, shared with GM_xmlhttpRequest and @require fetches.
            val call = ScriptoriaApp.instance.httpClient.newCall(requestBuilder.build())
            activeCalls[id] = call

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Server returned HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response body")

                // Prefer what the server actually says over the caller's guess.
                val mimeType = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                    ?: mimeHint?.takeIf { it.isNotBlank() }
                    ?: DownloadManagerRepository.guessMimeType(fallbackName)

                val name = contentDispositionName(response.header("Content-Disposition"))
                    ?: fallbackName

                val total = body.contentLength().takeIf { it > 0L }
                sink = DownloadSink.create(this, ScriptoriaApp.instance.downloadPreferences, name, mimeType)
                val destination = sink!!

                notifyProgress(id, destination.displayName, 0L, total)
                ActiveDownloads.progress(id, destination.displayName, 0L, total, 0L)
                toast("Downloading ${destination.displayName}…")

                var written = 0L
                var lastUpdate = System.currentTimeMillis()
                var lastBytes = 0L

                destination.openOutputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            if (cancelled[id] == true) throw CancelledException()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read

                            // Throttled so a fast transfer doesn't spend its time in the
                            // notification manager instead of on the socket.
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastUpdate
                            if (elapsed >= PROGRESS_INTERVAL_MS) {
                                val speed = (written - lastBytes) * 1000 / elapsed
                                lastUpdate = now
                                lastBytes = written
                                notifyProgress(id, destination.displayName, written, total)
                                ActiveDownloads.progress(
                                    id, destination.displayName, written, total, speed
                                )
                            }
                        }
                        output.flush()
                    }
                }

                if (cancelled[id] == true) throw CancelledException()

                destination.markComplete()
                notifyComplete(id, destination.displayName, written)
                ActiveDownloads.finish(id)
                UserscriptConsole.addLog(
                    level = LogLevel.INFO,
                    scriptId = null,
                    scriptName = "Downloader",
                    message = "Saved ${destination.displayName} " +
                        "(${DownloadManagerRepository.formatSize(written)})"
                )
                toast("Saved ${destination.displayName}")
            }
        } catch (e: CancelledException) {
            abandon(id, sink)
        } catch (e: Exception) {
            // An OkHttp call cancelled mid-read surfaces as a plain IOException.
            if (cancelled[id] == true) {
                abandon(id, sink)
            } else {
                Log.e(TAG, "Download failed: $url", e)
                sink?.discard()
                notifyFailed(id, sink?.displayName ?: fallbackName, e)
                // Kept in the list rather than dropped, so the screen can offer a retry.
                ActiveDownloads.fail(id, e.message ?: "Unknown error")
                UserscriptConsole.addLog(
                    level = LogLevel.ERROR,
                    scriptId = null,
                    scriptName = "Downloader",
                    message = "Failed to download $fallbackName: ${e.message}"
                )
            }
        }
    }

    /** Cancelled by the user: drop the partial file and every trace of it from the UI. */
    private fun abandon(id: Int, sink: DownloadSink?) {
        sink?.discard()
        notifications.cancel(id)
        ActiveDownloads.remove(id)
        toast("Download cancelled")
    }

    private fun stopIfIdle() {
        // Page-driven streams count too: they have no other owner keeping the process alive.
        if (activeCount.get() == 0 && StreamDownloads.sessionCount() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // region notifications

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                // Low: progress updates should not buzz the device on every tick.
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Ongoing and completed downloads" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildSummaryNotification(): android.app.Notification {
        val count = activeCount.get() + StreamDownloads.sessionCount()
        val text = when (count) {
            0 -> "Preparing download…"
            1 -> "1 download in progress"
            else -> "$count downloads in progress"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scriptoria")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** Keeps the persistent foreground entry honest as downloads start and finish. */
    private fun refreshSummary() {
        if (activeCount.get() > 0) {
            safeNotify(SUMMARY_NOTIFICATION_ID, buildSummaryNotification())
        }
    }

    private fun notifyProgress(id: Int, name: String, written: Long, total: Long?) {
        val cancelIntent = PendingIntent.getService(
            this,
            id,
            Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DOWNLOAD_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        if (total != null) {
            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
                .setContentText(
                    "$percent%  ·  ${DownloadManagerRepository.formatSize(written)} / " +
                        DownloadManagerRepository.formatSize(total)
                )
        } else {
            // Server sent no Content-Length, so percentage is unknowable.
            builder.setProgress(0, 0, true)
                .setContentText(DownloadManagerRepository.formatSize(written))
        }

        safeNotify(id, builder.build())
    }

    private fun notifyComplete(id: Int, name: String, size: Long) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download complete")
            .setContentText("$name (${DownloadManagerRepository.formatSize(size)})")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        safeNotify(id, notification)
    }

    private fun notifyFailed(id: Int, name: String, e: Exception) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download failed")
            .setContentText("$name — ${e.message ?: "unknown error"}")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        safeNotify(id, notification)
    }

    private fun safeNotify(id: Int, notification: android.app.Notification) {
        try {
            notifications.notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; the transfer itself is unaffected.
            Log.w(TAG, "Notification suppressed: ${e.message}")
        }
    }

    // endregion

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private class CancelledException : IOException("cancelled")

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "scriptoria_downloads"
        private const val SUMMARY_NOTIFICATION_ID = 0x5C01
        private const val MAX_PARALLEL = 3
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 500L

        const val ACTION_ENQUEUE = "com.scriptoria.browser.action.DOWNLOAD_ENQUEUE"
        const val ACTION_CANCEL = "com.scriptoria.browser.action.DOWNLOAD_CANCEL"
        const val ACTION_HOLD = "com.scriptoria.browser.action.DOWNLOAD_HOLD"
        const val ACTION_RELEASE = "com.scriptoria.browser.action.DOWNLOAD_RELEASE"
        const val EXTRA_URL = "url"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_MIME = "mime"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_DOWNLOAD_ID = "download_id"

        fun enqueue(
            context: Context,
            url: String,
            fileName: String?,
            mimeType: String?,
            userAgent: String?,
            referer: String? = null
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILENAME, fileName)
                putExtra(EXTRA_MIME, mimeType)
                putExtra(EXTRA_USER_AGENT, userAgent)
                putExtra(EXTRA_REFERER, referer)
            }
            context.startForegroundService(intent)
        }

        /**
         * Keeps the service foregrounded while a page-driven stream runs. Those bytes come
         * from JS, so nothing else stops Android killing the browser mid-transfer.
         */
        fun hold(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, DownloadService::class.java).apply { action = ACTION_HOLD }
                )
            } catch (e: Exception) {
                // Backgrounded apps may not start a foreground service; the transfer still
                // runs, it just loses the protection.
                Log.w(TAG, "Could not hold foreground service: ${e.message}")
            }
        }

        /** Lets the service stop once nothing else is running. */
        fun release(context: Context) {
            try {
                context.startService(
                    Intent(context, DownloadService::class.java).apply { action = ACTION_RELEASE }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not release foreground service: ${e.message}")
            }
        }

        /** Cancels an in-flight download. Failed entries are already done — just remove those. */
        fun cancel(context: Context, id: Int) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DOWNLOAD_ID, id)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Service already stopped; nothing is running to cancel.
                Log.w(TAG, "Cancel ignored for #$id: ${e.message}")
                ActiveDownloads.remove(id)
            }
        }

        private fun guessNameFromUrl(url: String): String =
            url.substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')
                .ifBlank { "download" }

        /** Extracts a filename from Content-Disposition, preferring RFC 5987 `filename*`. */
        private fun contentDispositionName(header: String?): String? {
            if (header.isNullOrBlank()) return null

            Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
                .find(header)
                ?.groupValues?.get(1)
                ?.let { encoded ->
                    return try {
                        DownloadSink.sanitizeFileName(java.net.URLDecoder.decode(encoded, "UTF-8"))
                    } catch (e: Exception) {
                        null
                    }
                }

            return Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(header)
                ?.groupValues?.get(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { DownloadSink.sanitizeFileName(it) }
        }
    }
}
