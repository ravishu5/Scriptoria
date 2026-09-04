package com.scriptoria.browser.engine.network

import android.content.Context
import android.util.Log
import com.scriptoria.browser.data.preferences.DownloadPreferences
import com.scriptoria.browser.data.repository.DownloadSink
import com.scriptoria.browser.engine.media.MediaRemuxer
import com.scriptoria.browser.engine.network.MediaHttp.applyMediaHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads HLS (.m3u8) streams by resolving segments, optionally decrypting AES-128,
 * and concatenating them into a single playable media file.
 */
object HlsDownloader {

    private const val TAG = "HlsDownloader"

    data class HlsSegment(
        val url: String,
        val duration: Double,
        val keyUrl: String? = null,
        val keyIv: String? = null
    )

    fun download(
        context: Context,
        id: Int,
        m3u8Url: String,
        requestedName: String,
        userAgent: String?,
        referer: String?,
        httpClient: OkHttpClient,
        preferences: DownloadPreferences,
        isCancelled: () -> Boolean,
        onProgressUpdate: (written: Long, total: Long?, speed: Long) -> Unit
    ) {
        var sink: DownloadSink? = null
        var totalWritten = 0L
        var workFile: File? = null
        var muxedFile: File? = null

        try {
            if (isCancelled()) return

            // 1. Fetch playlist content
            val playlistText = fetchString(m3u8Url, userAgent, referer, httpClient)
                ?: throw IOException("Failed to load HLS playlist: $m3u8Url")

            if (isCancelled()) return

            // 2. If it's a master playlist, pick the best variant
            val (mediaPlaylistUrl, mediaPlaylistText) = if (playlistText.contains("#EXT-X-STREAM-INF")) {
                val variantUrl = pickBestVariantUrl(playlistText, m3u8Url)
                    ?: throw IOException("No media stream found in master playlist")
                val text = fetchString(variantUrl, userAgent, referer, httpClient)
                    ?: throw IOException("Failed to load variant playlist: $variantUrl")
                variantUrl to text
            } else {
                m3u8Url to playlistText
            }

            if (isCancelled()) return

            // 3. Parse segments
            val playlist = parsePlaylist(mediaPlaylistText, mediaPlaylistUrl)
            val segments = playlist.segments
            if (segments.isEmpty()) {
                throw IOException("No segments found in HLS playlist")
            }

            // Segments are assembled in the cache first. Concatenating them yields whatever
            // container the segments use — MPEG-TS, or fragmented MP4 when there is an
            // initialisation segment — and neither is what a user expects a downloaded video to
            // be, so the finished stream is rewrapped before it reaches the download folder.
            workFile = File(
                File(context.cacheDir, "hls").apply { mkdirs() },
                "$id-stream.tmp"
            )

            workFile.outputStream().buffered().use { outputStream ->
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastSpeedCalc = 0L
                var currentSpeed = 0L
                val keyCache = mutableMapOf<String, ByteArray>()

                playlist.initSegmentUrl?.let { initUrl ->
                    val initBytes = fetchBytesWithRetry(initUrl, userAgent, referer, httpClient, isCancelled)
                        ?: throw IOException("Could not fetch initialisation segment: $initUrl")
                    outputStream.write(initBytes)
                    totalWritten += initBytes.size
                }

                for ((index, segment) in segments.withIndex()) {
                    if (isCancelled()) {
                        ActiveDownloads.remove(id)
                        return
                    }

                    // A skipped segment leaves a silent hole in the middle of the video while the
                    // download still reports success, so a segment that will not come down after
                    // several tries fails the whole download instead.
                    val segmentBytes =
                        fetchBytesWithRetry(segment.url, userAgent, referer, httpClient, isCancelled)
                            ?: if (isCancelled()) {
                                ActiveDownloads.remove(id)
                                return
                            } else {
                                throw IOException(
                                    "Segment ${index + 1} of ${segments.size} could not be downloaded"
                                )
                            }

                    val finalBytes = if (segment.keyUrl != null) {
                        val keyData = keyCache.getOrPut(segment.keyUrl) {
                            fetchBytesWithRetry(segment.keyUrl, userAgent, referer, httpClient, isCancelled)
                                ?: throw IOException("Could not fetch AES-128 key from ${segment.keyUrl}")
                        }
                        // Absent an explicit IV the spec derives it from the segment's media
                        // sequence number, which is not the same as its index unless the playlist
                        // happens to start at zero.
                        val ivBytes = segment.keyIv?.let { parseHexIv(it) }
                            ?: createSequenceIv(playlist.mediaSequence + index)
                        aesDecrypt(segmentBytes, keyData, ivBytes)
                    } else {
                        segmentBytes
                    }

                    outputStream.write(finalBytes)
                    totalWritten += finalBytes.size
                    bytesSinceLastSpeedCalc += finalBytes.size

                    val now = System.currentTimeMillis()
                    val delta = now - lastSpeedCalcTime
                    if (delta >= 1000L) {
                        currentSpeed = (bytesSinceLastSpeedCalc * 1000L) / delta
                        lastSpeedCalcTime = now
                        bytesSinceLastSpeedCalc = 0L
                    }

                    val estimatedTotal =
                        ((totalWritten.toDouble() / (index + 1)) * segments.size).toLong()

                    ActiveDownloads.progress(
                        id = id,
                        // The sink does not exist yet; its name is only settled once the stream
                        // is on disk and we know whether it could be rewrapped.
                        name = requestedName,
                        written = totalWritten,
                        total = estimatedTotal,
                        bytesPerSecond = currentSpeed
                    )
                    onProgressUpdate(totalWritten, estimatedTotal, currentSpeed)
                }

                outputStream.flush()
            }

            if (isCancelled()) {
                ActiveDownloads.remove(id)
                return
            }

            val baseName = requestedName.substringBeforeLast('.', requestedName)
            muxedFile = File(workFile.parentFile, "$id-muxed.mp4")

            // A rewrap can fail on a codec this device cannot put in an MP4. The stream is
            // already on disk and playable as-is at that point, so it is saved in its own
            // container rather than thrown away.
            val remuxed = try {
                MediaRemuxer.remux(listOf(workFile), muxedFile, isCancelled)
                muxedFile.length() > 0L
            } catch (e: Exception) {
                Log.w(TAG, "Keeping the raw stream; it could not be rewrapped: ${e.message}")
                false
            }

            if (isCancelled()) {
                ActiveDownloads.remove(id)
                return
            }

            val source = if (remuxed) muxedFile else workFile
            val isFragmentedMp4 = playlist.initSegmentUrl != null ||
                segments.first().url.substringBefore('?').endsWith(".m4s", ignoreCase = true)
            val extension = when {
                remuxed || isFragmentedMp4 -> "mp4"
                else -> "ts"
            }
            val mimeType = if (extension == "mp4") "video/mp4" else "video/mp2t"

            sink = DownloadSink.create(
                context = context,
                preferences = preferences,
                requestedName = "$baseName.$extension",
                mimeType = mimeType
            )
            sink.openOutputStream().use { output ->
                source.inputStream().buffered().use { it.copyTo(output) }
                output.flush()
            }

            sink.markComplete()
            ActiveDownloads.finish(id)
            Log.i(TAG, "HLS download complete: ${sink.displayName} (${source.length()} bytes)")

        } catch (e: Exception) {
            Log.e(TAG, "Error during HLS download", e)
            sink?.discard()
            if (isCancelled()) {
                ActiveDownloads.remove(id)
            } else {
                ActiveDownloads.fail(id, e.message ?: "HLS download failed")
            }
        } finally {
            // Full copies of the media; leaving them behind would quietly fill the cache.
            listOf(workFile, muxedFile).forEach { file -> runCatching { file?.delete() } }
        }
    }

    /**
     * A media playlist: its segments plus the two things needed to write a correct file — the
     * fMP4 initialisation segment, and the media sequence number the default AES IV derives from.
     */
    data class MediaPlaylist(
        val segments: List<HlsSegment>,
        val initSegmentUrl: String?,
        val mediaSequence: Long
    )

    private fun parsePlaylist(content: String, baseUrl: String): MediaPlaylist {
        val lines = content.lines()
        val segments = mutableListOf<HlsSegment>()
        var currentKeyUrl: String? = null
        var currentKeyIv: String? = null
        var initSegmentUrl: String? = null
        var mediaSequence = 0L

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-KEY:")) {
                val attrs = line.substringAfter(':')
                // METHOD=NONE turns encryption back off part-way through a playlist.
                val method = extractAttr(attrs, "METHOD")
                if (method != null && method.equals("NONE", ignoreCase = true)) {
                    currentKeyUrl = null
                    currentKeyIv = null
                } else {
                    currentKeyUrl = extractAttr(attrs, "URI")?.let { resolveUrl(it, baseUrl) }
                    currentKeyIv = extractAttr(attrs, "IV")
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                // fMP4 streams put the moov box in a separate initialisation segment. Without it
                // first, the concatenated media segments are not a playable file at all.
                initSegmentUrl = extractAttr(line.substringAfter(':'), "URI")
                    ?.let { resolveUrl(it, baseUrl) }
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
            } else if (line.startsWith("#EXTINF:")) {
                val duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                i++
                while (i < lines.size && (lines[i].trim().isEmpty() || lines[i].trim().startsWith("#"))) {
                    i++
                }
                if (i < lines.size) {
                    val segUrl = lines[i].trim()
                    segments.add(
                        HlsSegment(
                            url = resolveUrl(segUrl, baseUrl),
                            duration = duration,
                            keyUrl = currentKeyUrl,
                            keyIv = currentKeyIv
                        )
                    )
                }
            }
            i++
        }
        return MediaPlaylist(segments, initSegmentUrl, mediaSequence)
    }

    private fun pickBestVariantUrl(masterContent: String, baseUrl: String): String? {
        val lines = masterContent.lines()
        var bestBw = -1L
        var bestUrl: String? = null

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = line.substringAfter(':')
                val bw = Regex("""BANDWIDTH=(\d+)""").find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                i++
                while (i < lines.size && (lines[i].trim().isEmpty() || lines[i].trim().startsWith("#"))) {
                    i++
                }
                if (i < lines.size) {
                    val vUrl = lines[i].trim()
                    if (bw >= bestBw || bestUrl == null) {
                        bestBw = bw
                        bestUrl = resolveUrl(vUrl, baseUrl)
                    }
                }
            }
            i++
        }
        return bestUrl
    }

    /**
     * A single dropped segment corrupts the whole file, and a long download over a mobile
     * connection will drop one, so each is retried before giving up on it.
     */
    private fun fetchBytesWithRetry(
        url: String,
        userAgent: String?,
        referer: String?,
        client: OkHttpClient,
        isCancelled: () -> Boolean,
        attempts: Int = 3
    ): ByteArray? {
        for (attempt in 1..attempts) {
            if (isCancelled()) return null
            fetchBytes(url, userAgent, referer, client)?.let { return it }
            if (attempt < attempts) {
                // Back off a little; the usual cause is a transient network blip.
                try { Thread.sleep(500L * attempt) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }

    private fun fetchString(url: String, userAgent: String?, referer: String?, client: OkHttpClient): String? {
        return try {
            val req = Request.Builder().url(url)
                .applyMediaHeaders(url, userAgent, referer)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchBytes(url: String, userAgent: String?, referer: String?, client: OkHttpClient): ByteArray? {
        return try {
            val req = Request.Builder().url(url)
                .applyMediaHeaders(url, userAgent, referer)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAttr(attrs: String, key: String): String? {
        val regex = Regex("""$key="?([^",]+)"?""", RegexOption.IGNORE_CASE)
        return regex.find(attrs)?.groupValues?.getOrNull(1)
    }

    private fun resolveUrl(urlToResolve: String, baseUrl: String): String {
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(urlToResolve).toString()
        } catch (_: Exception) {
            when {
                urlToResolve.startsWith("http://") || urlToResolve.startsWith("https://") -> urlToResolve
                urlToResolve.startsWith("/") -> {
                    val origin = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+")
                        .find(baseUrl)?.value ?: baseUrl
                    "$origin$urlToResolve"
                }
                else -> {
                    val prefix = baseUrl.substringBeforeLast('/', "")
                    if (prefix.isNotEmpty()) "$prefix/$urlToResolve" else urlToResolve
                }
            }
        }
    }

    private fun parseHexIv(hex: String): ByteArray {
        val cleanHex = hex.removePrefix("0x").removePrefix("0X")
        return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /** RFC 8216: the default IV is the segment's media sequence number, big-endian in 16 bytes. */
    private fun createSequenceIv(sequenceNumber: Long): ByteArray {
        return ByteArray(16).apply {
            for (byteIndex in 0 until 8) {
                this[15 - byteIndex] = (sequenceNumber ushr (8 * byteIndex)).toByte()
            }
        }
    }

    private fun aesDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key.take(16).toByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.take(16).toByteArray())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        return cipher.doFinal(data)
    }
}
