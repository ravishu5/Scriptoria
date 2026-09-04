package com.scriptoria.browser.engine.network

import android.content.Context
import android.util.Log
import com.scriptoria.browser.data.preferences.DownloadPreferences
import com.scriptoria.browser.data.repository.DownloadSink
import com.scriptoria.browser.engine.media.MediaRemuxer
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * Downloads a video-only and an audio-only stream and rewraps them into one playable MP4.
 *
 * Sites that stream adaptively — YouTube above 720p, most DASH players — never serve the two
 * tracks together, so there is no single URL to fetch. Both are pulled to the cache, muxed, and
 * only the finished file is handed to the download sink.
 */
object MuxedDownloader {

    private const val TAG = "MuxedDownloader"

    /** The rewrap is fast next to the transfer, so it gets a small slice of the progress bar. */
    private const val DOWNLOAD_SHARE = 0.9f

    fun download(
        context: Context,
        id: Int,
        videoUrl: String,
        audioUrl: String,
        requestedName: String,
        userAgent: String?,
        referer: String?,
        httpClient: OkHttpClient,
        preferences: DownloadPreferences,
        isCancelled: () -> Boolean,
        onProgressUpdate: (written: Long, total: Long?, speed: Long) -> Unit
    ) {
        val workDir = File(context.cacheDir, "mux").apply { mkdirs() }
        val videoFile = File(workDir, "$id-video.tmp")
        val audioFile = File(workDir, "$id-audio.tmp")
        val muxedFile = File(workDir, "$id-muxed.mp4")
        var sink: DownloadSink? = null

        try {
            val startedAt = System.currentTimeMillis()
            var videoBytes = 0L
            var audioBytes = 0L

            fun report() {
                val written = videoBytes + audioBytes
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                val speed = written * 1000L / elapsed
                ActiveDownloads.progress(id, requestedName, written, null, speed)
                onProgressUpdate(written, null, speed)
            }

            MediaHttp.downloadToFile(videoUrl, videoFile, userAgent, referer, httpClient, isCancelled) {
                videoBytes = it
                report()
            }
            if (isCancelled()) return

            MediaHttp.downloadToFile(audioUrl, audioFile, userAgent, referer, httpClient, isCancelled) {
                audioBytes = it
                report()
            }
            if (isCancelled()) return

            if (videoFile.length() == 0L || audioFile.length() == 0L) {
                throw IOException("One of the streams downloaded empty")
            }

            val downloadedBytes = videoBytes + audioBytes
            MediaRemuxer.remux(
                inputs = listOf(videoFile, audioFile),
                output = muxedFile,
                isCancelled = isCancelled
            ) { fraction ->
                val written =
                    (downloadedBytes * (DOWNLOAD_SHARE + (1f - DOWNLOAD_SHARE) * fraction)).toLong()
                ActiveDownloads.progress(id, requestedName, written, downloadedBytes, 0L)
                onProgressUpdate(written, downloadedBytes, 0L)
            }
            if (isCancelled()) return

            val baseName = requestedName.substringBeforeLast('.', requestedName)
            sink = DownloadSink.create(
                context = context,
                preferences = preferences,
                requestedName = "$baseName.mp4",
                mimeType = "video/mp4"
            )
            sink.openOutputStream().use { output ->
                muxedFile.inputStream().buffered().use { it.copyTo(output) }
                output.flush()
            }

            sink.markComplete()
            ActiveDownloads.finish(id)
            Log.i(TAG, "Muxed download complete: ${sink.displayName} (${muxedFile.length()} bytes)")
        } catch (e: Throwable) {
            Log.e(TAG, "Muxed download failed", e)
            sink?.discard()
            if (isCancelled()) {
                ActiveDownloads.remove(id)
            } else {
                ActiveDownloads.fail(id, e.message ?: "Could not combine audio and video")
            }
        } finally {
            // These are full copies of the media; leaving them behind would quietly fill the cache.
            listOf(videoFile, audioFile, muxedFile).forEach { runCatching { it.delete() } }
            if (isCancelled()) {
                sink?.discard()
                ActiveDownloads.remove(id)
            }
        }
    }
}
