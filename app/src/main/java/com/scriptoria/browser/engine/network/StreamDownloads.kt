package com.scriptoria.browser.engine.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.scriptoria.browser.ScriptoriaApp
import com.scriptoria.browser.data.repository.DownloadManagerRepository
import com.scriptoria.browser.data.repository.DownloadSink
import com.scriptoria.browser.engine.console.LogLevel
import com.scriptoria.browser.engine.console.UserscriptConsole
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads whose bytes can only be produced by the page itself.
 *
 * Telegram Web (and anything else built on a service worker or blob: URLs) serves media that
 * has no fetchable origin — [DownloadService] cannot retrieve it, because the URL only resolves
 * inside the WebView. The page must do the fetching.
 *
 * The alternative was for a script to buffer the whole file in JS and hand it over as one
 * base64 string, which for a large video meant hundreds of megabytes of blob plus a third again
 * of base64 before anything reached disk — a long UI freeze and a real OOM risk. Here the file
 * is registered up front and written incrementally, so it appears in the Downloads screen the
 * instant the user clicks, progresses while it transfers, and never exists in memory as a whole.
 */
object StreamDownloads {

    private const val TAG = "StreamDownloads"
    private const val CHANNEL_ID = "scriptoria_downloads"

    private class Session(
        val id: Int,
        val sink: DownloadSink,
        val output: OutputStream,
        /** Not known at click time for a ranged fetch; filled in by [setTotal] once headers land. */
        var totalBytes: Long?
    ) {
        var written: Long = 0L
        var lastNotifiedAt: Long = 0L
    }

    private val sessions = ConcurrentHashMap<Int, Session>()

    /**
     * Opens a destination and registers the download so the UI can show it immediately.
     * Returns the session id, or -1 if the destination could not be created.
     *
     * [totalBytes] may be <= 0 when the page does not know the length yet.
     */
    fun begin(context: Context, fileName: String, mimeType: String?, totalBytes: Long): Int {
        val app = ScriptoriaApp.instance
        val safeName = DownloadSink.sanitizeFileName(fileName)
        val effectiveMime = mimeType?.takeIf { it.isNotBlank() }
            ?: DownloadManagerRepository.guessMimeType(safeName)

        return try {
            ensureChannel(context)
            val sink = DownloadSink.create(context, app.downloadPreferences, safeName, effectiveMime)
            val id = ActiveDownloads.nextId()
            val session = Session(id, sink, sink.openOutputStream(), totalBytes.takeIf { it > 0L })
            sessions[id] = session

            ActiveDownloads.start(id, sink.displayName, url = "", mimeType = effectiveMime, userAgent = null, referer = null)
            ActiveDownloads.progress(id, sink.displayName, 0L, session.totalBytes, 0L)
            notifyProgress(context, session, sink.displayName)
            Log.i(TAG, "begin #$id ${sink.displayName} mime=$effectiveMime total=${session.totalBytes ?: "unknown"}")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Could not begin stream download for $safeName", e)
            -1
        }
    }

    /**
     * Appends one chunk. Returns false if the session is gone (cancelled or already failed), so
     * the caller can stop fetching instead of transferring bytes nothing will keep.
     */
    fun write(context: Context, id: Int, bytes: ByteArray): Boolean {
        val session = sessions[id] ?: return false
        return try {
            session.output.write(bytes)
            session.written += bytes.size

            // Throttled: a fast stream would otherwise spend its time in the notification
            // manager and recomposing the list instead of writing.
            val now = System.currentTimeMillis()
            if (now - session.lastNotifiedAt >= PROGRESS_INTERVAL_MS) {
                val elapsed = (now - session.lastNotifiedAt).coerceAtLeast(1L)
                val speed = if (session.lastNotifiedAt == 0L) 0L else bytes.size * 1000L / elapsed
                session.lastNotifiedAt = now
                ActiveDownloads.progress(
                    id, session.sink.displayName, session.written, session.totalBytes, speed
                )
                notifyProgress(context, session, session.sink.displayName)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write failed for #$id", e)
            fail(context, id, e.message ?: "Write failed")
            false
        }
    }

    /**
     * Supplies the length once the page learns it. A ranged fetch only discovers the total from
     * the first Content-Range, but the download is registered on click so the user sees it
     * straight away — until this lands it just shows as indeterminate.
     */
    fun setTotal(context: Context, id: Int, total: Long) {
        val session = sessions[id] ?: return
        if (total <= 0L || session.totalBytes != null) return
        session.totalBytes = total
        ActiveDownloads.progress(id, session.sink.displayName, session.written, total, 0L)
        notifyProgress(context, session, session.sink.displayName)
    }

    fun finish(context: Context, id: Int) {
        val session = sessions.remove(id) ?: return
        try {
            session.output.flush()
            session.output.close()
            session.sink.markComplete()

            ActiveDownloads.finish(id)
            notifyComplete(context, id, session.sink.displayName, session.written)
            Log.i(TAG, "finish #$id ${session.sink.displayName} bytes=${session.written}")
            UserscriptConsole.addLog(
                level = LogLevel.INFO,
                scriptId = null,
                scriptName = "Downloader",
                message = "Saved ${session.sink.displayName} " +
                    "(${DownloadManagerRepository.formatSize(session.written)})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not finalise #$id", e)
            session.sink.discard()
            ActiveDownloads.fail(id, e.message ?: "Could not finalise file")
        }
    }

    /** User cancelled, or the page gave up. Drops the partial file. */
    fun abort(context: Context, id: Int) {
        val session = sessions.remove(id) ?: return
        closeQuietly(session)
        session.sink.discard()
        ActiveDownloads.remove(id)
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun fail(context: Context, id: Int, message: String) {
        val session = sessions.remove(id) ?: return
        closeQuietly(session)
        session.sink.discard()
        ActiveDownloads.fail(id, message)
        NotificationManagerCompat.from(context).cancel(id)
    }

    /** True while the page still has an open session — lets JS detect a cancel from the UI. */
    fun isActive(id: Int): Boolean = sessions.containsKey(id)

    private fun closeQuietly(session: Session) {
        try {
            session.output.close()
        } catch (e: Exception) {
            Log.w(TAG, "Close failed for #${session.id}: ${e.message}")
        }
    }

    // region notifications

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Ongoing and completed downloads" }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun notifyProgress(context: Context, session: Session, name: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)

        val total = session.totalBytes
        if (total != null) {
            val percent = ((session.written * 100) / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
                .setContentText(
                    "$percent%  ·  ${DownloadManagerRepository.formatSize(session.written)} / " +
                        DownloadManagerRepository.formatSize(total)
                )
        } else {
            builder.setProgress(0, 0, true)
                .setContentText(DownloadManagerRepository.formatSize(session.written))
        }

        safeNotify(context, session.id, builder.build())
    }

    private fun notifyComplete(context: Context, id: Int, name: String, size: Long) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download complete")
            .setContentText("$name (${DownloadManagerRepository.formatSize(size)})")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        // Same id as the progress notification, so it replaces it rather than stacking.
        safeNotify(context, id, notification)
    }

    private fun safeNotify(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; the transfer itself is unaffected.
            Log.w(TAG, "Notification suppressed: ${e.message}")
        }
    }

    // endregion

    private const val PROGRESS_INTERVAL_MS = 400L
}
