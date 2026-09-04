package com.scriptoria.browser.engine.media

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sniffs, classifies, and manages detected video assets per browser tab.
 */
class VideoDetectionManager(
    // The client rather than the Application: nothing else here needs app context, and taking
    // only what is used keeps the classification rules testable without an Android Application.
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "VideoDetectionManager"

        /**
         * A page can pull in a lot of media-shaped URLs; without a ceiling a long session on a
         * video site grows this list without bound and the sheet becomes unusable.
         */
        private const val MAX_VIDEOS_PER_TAB = 25

        /** Pieces of a stream rather than a file: never offered on their own. */
        private val SEGMENT_EXTENSIONS = listOf(".ts", ".m4s", ".aac", ".vtt", ".key")

        /** Sites whose videos are read from their own player data rather than sniffed. */
        private val EXTRACTOR_HOSTS = setOf(
            "youtube.com", "youtube-nocookie.com", "youtu.be", "googlevideo.com"
        )

        private val AD_URL_PATTERNS = listOf(
            "doubleclick.net", "googleadservices", "pagead", "/ads/", "/ad/",
            "adserver", "ad_stream", "adsystem", "adnxs", "rubiconproject",
            "openx.net", "spotxchange", "springserve", "freewheel", "liverail",
            "yieldmo", "smartadserver", "contextweb", "preroll", "midroll", "postroll"
        )
    }

    /**
     * Bumped whenever a tab's list is cleared. Detection is asynchronous — an HLS manifest fetch
     * takes long enough for the user to have navigated away — so a result carries the generation
     * it started under and is dropped if the tab has moved on since.
     */
    private val tabGenerations = ConcurrentHashMap<String, AtomicInteger>()

    private val _detectedVideosByTab = MutableStateFlow<Map<String, List<DetectedVideo>>>(emptyMap())
    val detectedVideosByTab: StateFlow<Map<String, List<DetectedVideo>>> = _detectedVideosByTab.asStateFlow()

    /**
     * Called when a network request is intercepted by WebViewClient.
     */
    fun onNetworkResourceIntercepted(
        tabId: String,
        pageUrl: String?,
        requestUrl: String,
        headers: Map<String, String> = emptyMap()
    ) {
        if (requestUrl.isBlank()) return
        if (isAdOrTracking(requestUrl)) return
        if (usesExtractor(pageUrl)) return

        val format = classifyFormat(requestUrl) ?: return

        scope.launch {
            handleDetectedMedia(
                tabId = tabId,
                pageUrl = pageUrl?.takeIf { it.isNotBlank() } ?: requestUrl,
                streamUrl = requestUrl,
                format = format,
                headers = headers,
                titleHint = null
            )
        }
    }

    /**
     * Called from DOM JavaScript bridge when <video> elements are found or start playing.
     */
    fun onDomMediaDetected(
        tabId: String,
        pageUrl: String,
        mediaUrl: String,
        mimeType: String? = null,
        title: String? = null,
        durationSeconds: Double? = null
    ) {
        if (mediaUrl.isBlank() || isAdOrTracking(mediaUrl)) return
        if (usesExtractor(pageUrl)) return

        // The URL decides, and a segment is rejected outright: the old rules here matched ".mp4"
        // anywhere in the URL and fell back to MP4 for anything left over, which resurrected every
        // segment classifyFormat had just rejected.
        val path = mediaUrl.substringBefore('?').substringBefore('#').lowercase()
        if (SEGMENT_EXTENSIONS.any { path.endsWith(it) }) return

        val format = classifyFormat(mediaUrl)
            ?: formatFromMime(mimeType)
            ?: return

        scope.launch {
            handleDetectedMedia(
                tabId = tabId,
                pageUrl = pageUrl.ifBlank { mediaUrl },
                streamUrl = mediaUrl,
                format = format,
                headers = emptyMap(),
                titleHint = title,
                durationSeconds = durationSeconds
            )
        }
    }

    /**
     * Formats a site published about its own video, rather than URLs guessed from traffic.
     *
     * This is the only way to get YouTube right: its media URLs are adaptive, so the video and
     * audio arrive as separate streams and any single sniffed URL downloads to a silent clip or a
     * bare audio track. The player response also lists the progressive renditions, which carry
     * both tracks in one file — those are what this reports.
     */
    fun onExtractedFormats(tabId: String, pageUrl: String, json: String) {
        val generation = generationOf(tabId).get()
        scope.launch {
            try {
                if (generationOf(tabId).get() != generation) return@launch
                val root = JSONObject(json)
                val videoId = root.optString("videoId").takeIf { it.isNotBlank() } ?: return@launch
                val progressive = readFormats(root.optJSONArray("formats"))
                val adaptive = readFormats(root.optJSONArray("adaptive"))

                // AAC in an MP4 wrapper is the one audio track every device can rewrap; Opus and
                // Vorbis depend on the API level, so pairing with them would produce a file that
                // muxes on some phones and not others.
                val audio = adaptive
                    .filter { it.mime.startsWith("audio/mp4") }
                    .maxByOrNull { it.bitrate }

                val progressiveQualities = progressive.map { it.toQuality() }

                // Adaptive video is only worth offering above what a progressive rendition already
                // covers — below that it costs a second download and a rewrap for nothing.
                val bestProgressiveHeight = progressive.maxOfOrNull { it.height } ?: 0
                val adaptiveQualities = if (audio == null) {
                    emptyList()
                } else {
                    adaptive
                        .filter { it.mime.startsWith("video/mp4") && it.height > bestProgressiveHeight }
                        .distinctBy { it.height }
                        .map { video ->
                            video.toQuality().copy(
                                audioStreamUrl = audio.url,
                                sizeBytes = video.contentLength?.let { v ->
                                    audio.contentLength?.let { a -> v + a } ?: v
                                }
                            )
                        }
                }

                val qualities = (progressiveQualities + adaptiveQualities)
                    .sortedByDescending { it.heightOrZero }

                if (qualities.isEmpty()) return@launch

                val detected = DetectedVideo(
                    id = UUID.randomUUID().toString(),
                    pageUrl = pageUrl,
                    // Not the watch URL: cleanUrl strips the query, so every video on the site
                    // would share the key "https://www.youtube.com/watch" and collapse into one.
                    masterUrl = "extracted://$videoId",
                    title = root.optString("title").ifBlank { "Video" },
                    format = VideoFormat.MP4,
                    qualities = qualities,
                    durationSeconds = root.optDouble("durationSeconds", 0.0).takeIf { it > 0 }
                )

                _detectedVideosByTab.update { map ->
                    val existing = map[tabId] ?: emptyList()
                    if (generationOf(tabId).get() != generation) {
                        map
                    } else if (existing.any { it.masterUrl == detected.masterUrl }) {
                        map
                    } else {
                        // Extracted formats are authoritative for this page; anything sniffed off
                        // the wire for it is a fragment of the same video.
                        map + (tabId to (existing.filterNot { it.pageUrl == pageUrl } + detected))
                    }
                }
                Log.i(TAG, "Extracted ${qualities.size} progressive formats for $videoId")
            } catch (e: Exception) {
                Log.w(TAG, "Could not read extracted formats: ${e.message}")
            }
        }
    }

    /**
     * A media type is only trusted when the URL itself says nothing — a player that reports
     * "video/mp4" while fetching a segment is describing the stream, not that one request.
     */
    private fun formatFromMime(mimeType: String?): VideoFormat? {
        val mime = mimeType?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            mime.contains("mpegurl") -> VideoFormat.HLS
            mime.contains("dash+xml") -> VideoFormat.DASH
            mime.contains("webm") -> VideoFormat.WEBM
            mime.contains("mp4") -> VideoFormat.MP4
            else -> null
        }
    }

    /** One entry of a site's published format list. */
    private data class ExtractedFormat(
        val url: String,
        val qualityLabel: String,
        val width: Int,
        val height: Int,
        val bitrate: Long,
        val contentLength: Long?,
        val mime: String
    ) {
        fun toQuality() = VideoQualityOption(
            quality = if (height > 0) {
                HlsManifestParser.qualityNameForHeight(height)
            } else {
                qualityLabel.ifBlank { "Source" }
            },
            resolution = if (width > 0 && height > 0) "${width}x$height" else null,
            bandwidth = bitrate,
            sizeBytes = contentLength,
            streamUrl = url,
            format = VideoFormat.MP4
        )
    }

    private fun readFormats(array: org.json.JSONArray?): List<ExtractedFormat> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ExtractedFormat(
                url = url,
                qualityLabel = item.optString("qualityLabel"),
                width = item.optInt("width", 0),
                height = item.optInt("height", 0),
                bitrate = item.optLong("bitrate", 0L),
                contentLength = item.optString("contentLength").toLongOrNull(),
                mime = item.optString("mimeType")
            )
        }
    }

    /**
     * Whether this page's videos come from [onExtractedFormats] instead of from sniffed traffic.
     *
     * Sniffing an adaptive site yields split audio and video tracks, so a URL caught here is not
     * downloadable on its own and would only crowd out the formats the extractor reports.
     */
    fun usesExtractor(pageUrl: String?): Boolean {
        val host = pageUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }
            ?.lowercase()
            ?: return false
        return EXTRACTOR_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Resets detected videos for a specific tab (e.g. on navigation).
     */
    fun clearTab(tabId: String) {
        generationOf(tabId).incrementAndGet()
        _detectedVideosByTab.update { map ->
            map - tabId
        }
    }

    private fun generationOf(tabId: String): AtomicInteger =
        tabGenerations.getOrPut(tabId) { AtomicInteger(0) }

    fun getVideosForTab(tabId: String): List<DetectedVideo> {
        return _detectedVideosByTab.value[tabId] ?: emptyList()
    }

    private suspend fun handleDetectedMedia(
        tabId: String,
        pageUrl: String,
        streamUrl: String,
        format: VideoFormat,
        headers: Map<String, String>,
        titleHint: String?,
        durationSeconds: Double? = null
    ) {
        val canonicalStreamUrl = cleanUrl(streamUrl)
        val generation = generationOf(tabId).get()

        // Checked again inside the update below, which is what actually makes it safe; this is
        // just to avoid the manifest fetch for something already known.
        val currentList = _detectedVideosByTab.value[tabId] ?: emptyList()
        if (currentList.size >= MAX_VIDEOS_PER_TAB) return
        if (currentList.any { it.covers(canonicalStreamUrl) }) return

        // The stream filename is usually "master", "index" or "playlist", which makes for a
        // useless label, so it is the last resort rather than the first.
        val videoTitle = titleHint?.takeIf { it.isNotBlank() }
            ?: extractTitleFromUrl(pageUrl)
            ?: extractTitleFromUrl(streamUrl)
            ?: "Video"

        var qualities = emptyList<VideoQualityOption>()

        if (format == VideoFormat.HLS) {
            // Attempt to fetch and parse master playlist for multiple qualities
            qualities = tryFetchHlsQualities(streamUrl, headers, durationSeconds)
        }

        if (qualities.isEmpty()) {
            val qualityHint = extractQualityLabel(streamUrl) ?: "Source HD"
            val contentLength = headers["Content-Length"]?.toLongOrNull()
                ?: headers["content-length"]?.toLongOrNull()

            qualities = listOf(
                VideoQualityOption(
                    quality = qualityHint,
                    resolution = null,
                    bandwidth = 0L,
                    sizeBytes = contentLength,
                    streamUrl = streamUrl,
                    format = format
                )
            )
        }

        val detected = DetectedVideo(
            id = UUID.randomUUID().toString(),
            pageUrl = pageUrl,
            masterUrl = streamUrl,
            title = videoTitle,
            format = format,
            qualities = qualities,
            headers = headers,
            durationSeconds = durationSeconds
        )

        // The page may have changed while the manifest was being fetched, in which case this
        // belongs to a document the user has already left.
        if (generationOf(tabId).get() != generation) return

        _detectedVideosByTab.update { map ->
            val existing = map[tabId] ?: emptyList()
            when {
                generationOf(tabId).get() != generation -> map
                existing.size >= MAX_VIDEOS_PER_TAB -> map
                existing.any { it.covers(canonicalStreamUrl) } -> map
                else -> {
                    // A master playlist supersedes any of its own renditions that were picked up
                    // before it: the player fetches 720p/index.m3u8 the moment it starts, and
                    // those would otherwise sit alongside the master as separate videos.
                    val renditions = detected.qualities.map { cleanUrl(it.streamUrl) }.toSet()
                    val pruned = existing.filterNot { cleanUrl(it.masterUrl) in renditions }
                    map + (tabId to (pruned + detected))
                }
            }
        }

        Log.i(TAG, "Added detected video in tab $tabId: '$videoTitle' ($format, ${qualities.size} qualities)")
    }

    private fun tryFetchHlsQualities(
        m3u8Url: String,
        headers: Map<String, String>,
        durationSeconds: Double?
    ): List<VideoQualityOption> {
        return try {
            val reqBuilder = Request.Builder().url(m3u8Url)
            headers["User-Agent"]?.let { reqBuilder.header("User-Agent", it) }
            headers["Referer"]?.let { reqBuilder.header("Referer", it) }
            // The intercepted request headers rarely carry the cookie, so fall back to the
            // browser's own jar — an authenticated stream 403s without it.
            val cookie = headers["Cookie"]
                ?: runCatching { android.webkit.CookieManager.getInstance().getCookie(m3u8Url) }
                    .getOrNull()
            cookie?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("Cookie", it) }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                response.close()
                return emptyList()
            }
            val content = response.body?.string().orEmpty()
            response.close()

            if (content.isNotBlank()) {
                val parsed = HlsManifestParser.parse(content, m3u8Url, durationSeconds)
                parsed.variants
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse HLS master playlist from $m3u8Url: ${e.message}")
            emptyList()
        }
    }

    /**
     * @return null for anything that is not independently downloadable.
     *
     * The extension is matched at the *end of the path*, never anywhere in the URL. Streaming
     * sites routinely name the segment directory after the source file — ".../video.mp4/seg-1.ts"
     * — so a substring match treats every segment of a stream as its own downloadable MP4, and a
     * few minutes of playback fills the sheet with copies of the same video.
     */
    fun classifyFormat(url: String): VideoFormat? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val lowerUrl = url.lowercase()

        // Segments: hundreds per stream, none downloadable alone. The manifest is the real thing.
        if (SEGMENT_EXTENSIONS.any { path.endsWith(it) }) return null

        return when {
            path.endsWith(".m3u8") || lowerUrl.contains("format=hls") -> VideoFormat.HLS
            path.endsWith(".mpd") -> VideoFormat.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") ||
                lowerUrl.contains("mime=video/mp4") || lowerUrl.contains("videoplayback") ->
                VideoFormat.MP4
            path.endsWith(".webm") || lowerUrl.contains("mime=video/webm") -> VideoFormat.WEBM
            path.endsWith(".mkv") || path.endsWith(".flv") || path.endsWith(".mov") ||
                path.endsWith(".avi") || path.endsWith(".3gp") -> VideoFormat.OTHER
            else -> null
        }
    }

    fun isAdOrTracking(url: String): Boolean {
        val lower = url.lowercase()
        if (AD_URL_PATTERNS.any { lower.contains(it) }) return true

        // Static images / tracking pixels
        val isImage = lower.endsWith(".gif") || lower.contains(".gif?") ||
                lower.endsWith(".png") || lower.contains(".png?") ||
                lower.endsWith(".jpg") || lower.contains(".jpg?") ||
                lower.endsWith(".jpeg") || lower.contains(".jpeg?") ||
                lower.endsWith(".webp") || lower.contains(".webp?") ||
                lower.endsWith(".svg") || lower.contains(".svg?") ||
                lower.endsWith(".ico") || lower.contains(".ico?")
        if (isImage) return true

        // Analytics / telemetry / short ping
        if (lower.contains("analytics") || lower.contains("telemetry") || lower.contains("/ping") || lower.contains("ping.gif") || lower.contains("favicon")) {
            return true
        }

        return false
    }

    /**
     * Whether this entry already accounts for [canonicalUrl] — either it is the entry's own
     * stream, or it is one of the renditions the entry offers as a quality. The second case is
     * what stops an HLS master and each of its variant playlists appearing as separate videos.
     */
    private fun DetectedVideo.covers(canonicalUrl: String): Boolean =
        cleanUrl(masterUrl) == canonicalUrl ||
            qualities.any { cleanUrl(it.streamUrl) == canonicalUrl }

    private fun cleanUrl(url: String): String =
        url.substringBefore('?').substringBefore('#').trimEnd('/')

    private fun extractTitleFromUrl(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val lastSegment = uri.lastPathSegment ?: return null
            val rawName = lastSegment.substringBeforeLast('.')
            if (rawName.isBlank() || rawName.length <= 2) return null
            rawName.replace('-', ' ').replace('_', ' ').capitalizeWords()
        } catch (_: Exception) {
            null
        }
    }

    private fun extractQualityLabel(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains("2160p") || lower.contains("3840x2160") -> "4K (2160p)"
            lower.contains("1440p") || lower.contains("2560x1440") -> "2K (1440p)"
            lower.contains("1080p") || lower.contains("1920x1080") -> "1080p (Full HD)"
            lower.contains("720p") || lower.contains("1280x720") -> "720p (HD)"
            lower.contains("480p") || lower.contains("854x480") || lower.contains("640x480") -> "480p (SD)"
            lower.contains("360p") || lower.contains("640x360") -> "360p"
            lower.contains("240p") -> "240p"
            else -> null
        }
    }

    private fun String.capitalizeWords(): String =
        split(' ').joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
}
