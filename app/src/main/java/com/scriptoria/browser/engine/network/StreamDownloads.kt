package com.scriptoria.browser.engine.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.scriptoria.browser.ScriptoriaApp
import com.scriptoria.browser.data.repository.DownloadManagerRepository
import com.scriptoria.browser.data.repository.DownloadSink
import com.scriptoria.browser.engine.console.LogLevel
import com.scriptoria.browser.engine.console.UserscriptConsole
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
        /**
         * Writes run here, not on the JS bridge thread. A synchronous write would block all
         * JavaScript — including the page's own network callbacks — so the transfer could
         * never overlap disk I/O with fetching. Single-threaded, so chunks stay in order.
         */
        val writer: ExecutorService = Executors.newSingleThreadExecutor()
        val queuedBytes = AtomicLong(0)

        @Volatile
        var failure: String? = null

        // Touched only on the writer thread.
        var written: Long = 0L
        var lastNotifiedAt: Long = 0L
        var lastNotifiedBytes: Long = 0L
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

            // Keep the process alive for the duration. The page produces these bytes, so
            // without a foreground service Android is free to kill the browser mid-transfer.
            DownloadService.hold(context)
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
        session.failure?.let { fail(context, id, it); return false }

        // Backpressure: let the page run ahead of the disk, but not without limit, or a fast
        // network would pull the whole file into memory and undo the point of streaming.
        val deadline = System.currentTimeMillis() + BACKPRESSURE_TIMEOUT_MS
        while (session.queuedBytes.get() >= MAX_QUEUED_BYTES) {
            if (!sessions.containsKey(id)) return false
            session.failure?.let { fail(context, id, it); return false }
            if (System.currentTimeMillis() > deadline) break
            try {
                Thread.sleep(4)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        session.queuedBytes.addAndGet(bytes.size.toLong())
        return try {
            session.writer.execute {
                try {
                    session.output.write(bytes)
                    session.written += bytes.size

                    // Throttled: a fast stream would otherwise spend its time in the
                    // notification manager and recomposing the list instead of writing.
                    val now = System.currentTimeMillis()
                    if (now - session.lastNotifiedAt >= PROGRESS_INTERVAL_MS) {
                        val elapsed = (now - session.lastNotifiedAt).coerceAtLeast(1L)
                        val speed = if (session.lastNotifiedAt == 0L) {
                            0L
                        } else {
                            (session.written - session.lastNotifiedBytes) * 1000L / elapsed
                        }
                        session.lastNotifiedAt = now
                        session.lastNotifiedBytes = session.written
                        ActiveDownloads.progress(
                            id, session.sink.displayName, session.written, session.totalBytes, speed
                        )
                        notifyProgress(context, session, session.sink.displayName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Write failed for #$id", e)
                    session.failure = e.message ?: "Write failed"
                } finally {
                    session.queuedBytes.addAndGet(-bytes.size.toLong())
                }
            }
            true
        } catch (e: RejectedExecutionException) {
            // Session torn down between the lookup and the submit.
            session.queuedBytes.addAndGet(-bytes.size.toLong())
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
            // Queued chunks are still in flight on the writer thread; publishing before they
            // land would truncate the file.
            session.writer.shutdown()
            if (!session.writer.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("Timed out flushing buffered chunks")
            }
            session.failure?.let { throw IOException(it) }

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
        } finally {
            releaseHoldIfIdle(context)
        }
    }

    /** User cancelled, or the page gave up. Drops the partial file. */
    fun abort(context: Context, id: Int) {
        val session = sessions.remove(id) ?: return
        closeQuietly(session)
        session.sink.discard()
        ActiveDownloads.remove(id)
        NotificationManagerCompat.from(context).cancel(id)
        // Logged, because a silent abort is indistinguishable from a hang when reading logcat.
        Log.i(TAG, "abort #$id ${session.sink.displayName} after ${session.written} bytes")
        releaseHoldIfIdle(context)
    }

    private fun fail(context: Context, id: Int, message: String) {
        val session = sessions.remove(id) ?: return
        closeQuietly(session)
        session.sink.discard()
        ActiveDownloads.fail(id, message)
        NotificationManagerCompat.from(context).cancel(id)
        Log.w(TAG, "fail #$id ${session.sink.displayName} after ${session.written} bytes: $message")
        releaseHoldIfIdle(context)
    }

    /** True while the page still has an open session — lets JS detect a cancel from the UI. */
    fun isActive(id: Int): Boolean = sessions.containsKey(id)

    /** How many page-driven transfers are open; [DownloadService] uses this to stay alive. */
    fun sessionCount(): Int = sessions.size

    /**
     * Releases the service's hold once the last session ends. Called from every terminal path
     * (finish, abort, fail) so the foreground notification never outlives the work.
     */
    private fun releaseHoldIfIdle(context: Context) {
        if (sessions.isEmpty()) DownloadService.release(context)
    }

    /**
     * Clears wreckage left by a previous process.
     *
     * Sessions live only in memory, so nothing survives a restart: any download notification
     * still on screen, and any MediaStore entry still marked pending, belongs to a transfer
     * that died with its process. Run once at startup, before any session can exist — if the
     * app was swiped away mid-download, this is what stops a dead progress notification and an
     * invisible half-written file from hanging around.
     */
    fun clearOrphans(context: Context) {
        cancelStaleNotifications(context)
        deleteStalePartials(context)
    }

    private fun cancelStaleNotifications(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.activeNotifications
                .filter { it.notification.channelId == CHANNEL_ID }
                .forEach { manager.cancel(it.id) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not sweep stale notifications: ${e.message}")
        }
    }

    private fun deleteStalePartials(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.IS_PENDING} = 1"

            // Scoped storage limits this to entries this app owns, so no other app's
            // in-flight download can be caught by the sweep.
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolver.query(collection, projection, Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                }, null)
            } else {
                resolver.query(collection, projection, selection, null, null)
            }

            var removed = 0
            cursor?.use { rows ->
                val idColumn = rows.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameColumn = rows.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (rows.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, rows.getLong(idColumn))
                    val name = rows.getString(nameColumn)
                    try {
                        resolver.delete(uri, null, null)
                        removed++
                        Log.i(TAG, "Cleared orphaned partial: $name")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not delete orphaned partial $name: ${e.message}")
                    }
                }
            }
            if (removed > 0) Log.i(TAG, "Cleared $removed orphaned partial download(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Orphan sweep failed: ${e.message}")
        }
    }

    /** Drops any queued chunks and closes the file — for paths that discard it anyway. */
    private fun closeQuietly(session: Session) {
        try {
            session.writer.shutdownNow()
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
            // Deliberately NOT ongoing. These bytes come from the page, so no service owns the
            // transfer: if the process dies mid-download an ongoing notification would outlive
            // it with nothing left to clear it, and the user could not even swipe it away.
            .setOngoing(false)
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

    /** How far the page may run ahead of the disk before [write] makes it wait. */
    private const val MAX_QUEUED_BYTES = 16L * 1024 * 1024
    private const val BACKPRESSURE_TIMEOUT_MS = 15_000L
    private const val DRAIN_TIMEOUT_SECONDS = 120L
}
