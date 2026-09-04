package com.scriptoria.browser.engine.media

import java.util.Locale

enum class VideoFormat {
    MP4,
    WEBM,
    HLS,
    DASH,
    OTHER;

    val displayName: String
        get() = when (this) {
            MP4 -> "MP4"
            WEBM -> "WebM"
            HLS -> "HLS (m3u8)"
            DASH -> "DASH"
            OTHER -> "Video"
        }
}

data class VideoQualityOption(
    val quality: String,             // e.g. "1080p Full HD", "720p HD", "480p", "360p", "Auto / Source"
    val resolution: String? = null,  // e.g. "1920x1080"
    val bandwidth: Long = 0L,        // bits per second
    val sizeBytes: Long? = null,     // Estimated or Content-Length size
    val streamUrl: String,           // Direct MP4 URL or HLS variant URL
    /**
     * Set when [streamUrl] carries video only and the audio arrives separately, as it does for
     * every adaptive rendition above 720p. Both are downloaded and rewrapped into one MP4.
     */
    val audioStreamUrl: String? = null,
    val format: VideoFormat = VideoFormat.MP4
) {
    /** True when this rendition has to be assembled from two downloads. */
    val needsMuxing: Boolean get() = audioStreamUrl != null

    val formattedSize: String?
        get() = sizeBytes?.let { formatBytes(it) }

    /** Vertical resolution, from the parsed "WIDTHxHEIGHT" or failing that the quality label. */
    val heightOrZero: Int
        get() = resolution?.substringAfter('x')?.toIntOrNull()
            ?: Regex("""(\d{3,4})p""").find(quality)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return ""
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                // Locale.US, not the default: a comma decimal separator in a file size reads
                // as a thousands separator to most people.
                gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
                else -> "$bytes B"
            }
        }
    }
}

data class DetectedVideo(
    val id: String,                  // Unique identifier for the video asset
    val pageUrl: String,             // Webpage where video was discovered
    val masterUrl: String,           // Main stream or manifest URL
    val title: String,               // Video title or page title
    val format: VideoFormat,
    val qualities: List<VideoQualityOption>, // All available resolutions/qualities
    val headers: Map<String, String> = emptyMap(), // Request headers: User-Agent, Referer, Cookie
    val durationSeconds: Double? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Best available quality.
     *
     * Ranked on the parsed resolution and bandwidth rather than on digits pulled out of the
     * label: that scored "5000 kbps" above "1080p (Full HD)", and read "4K (2160p)" as 42160.
     */
    val bestQuality: VideoQualityOption?
        get() = qualities.maxWithOrNull(
            compareBy<VideoQualityOption> { it.heightOrZero }.thenBy { it.bandwidth }
        )
}
