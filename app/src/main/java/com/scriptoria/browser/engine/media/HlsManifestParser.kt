package com.scriptoria.browser.engine.media

import java.net.URI

object HlsManifestParser {

    private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
    private val NAME_REGEX = Regex("""NAME="?([^",]+)"?""", RegexOption.IGNORE_CASE)

    data class ParsedHls(
        val isMaster: Boolean,
        val variants: List<VideoQualityOption>,
        val segmentsCount: Int = 0,
        /** Sum of the segment durations; zero for a master playlist, which has none. */
        val totalDurationSeconds: Double = 0.0
    )

    /**
     * Parses the string content of an .m3u8 playlist.
     *
     * @param manifestContent Raw UTF-8 text of the playlist.
     * @param baseUrl URL from which this manifest was fetched.
     * @param totalDurationSeconds Optional overall video duration to compute estimated file sizes.
     */
    fun parse(
        manifestContent: String,
        baseUrl: String,
        totalDurationSeconds: Double? = null
    ): ParsedHls {
        val lines = manifestContent.lines().map { it.trim() }
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }

        if (!isMaster) {
            val hasSegments = lines.any { it.startsWith("#EXTINF") }
            val segCount = lines.count { it.startsWith("#EXTINF") }
            // Summing the segment durations is the only way to know how long a media playlist is,
            // and without it no size can be estimated for a direct .m3u8.
            val duration = lines.filter { it.startsWith("#EXTINF") }
                .sumOf { it.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0 }
            val defaultOption = VideoQualityOption(
                quality = "Auto / Source",
                resolution = null,
                bandwidth = 0L,
                sizeBytes = null,
                streamUrl = baseUrl,
                format = VideoFormat.HLS
            )
            return ParsedHls(
                isMaster = false,
                variants = if (hasSegments) listOf(defaultOption) else emptyList(),
                segmentsCount = segCount,
                totalDurationSeconds = duration
            )
        }

        val variants = mutableListOf<VideoQualityOption>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = line.substringAfter(':')
                val resMatch = RESOLUTION_REGEX.find(attrs)
                val width = resMatch?.groupValues?.get(1)?.toIntOrNull()
                val height = resMatch?.groupValues?.get(2)?.toIntOrNull()
                val resStr = if (width != null && height != null) "${width}x${height}" else null

                val bandwidth = BANDWIDTH_REGEX.find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val name = NAME_REGEX.find(attrs)?.groupValues?.get(1)

                // Format quality display name
                val qualityLabel = when {
                    height != null -> qualityNameForHeight(height)
                    !name.isNullOrBlank() -> name
                    bandwidth > 0 -> "${bandwidth / 1000} kbps"
                    else -> "Unknown Quality"
                }

                // Look for the next non-empty, non-comment line containing the variant stream URL
                i++
                while (i < lines.size && (lines[i].isEmpty() || lines[i].startsWith("#"))) {
                    i++
                }

                if (i < lines.size) {
                    val rawStreamUrl = lines[i]
                    val resolvedUrl = resolveUrl(rawStreamUrl, baseUrl)
                    val estimatedBytes = if (totalDurationSeconds != null && bandwidth > 0) {
                        ((bandwidth / 8.0) * totalDurationSeconds).toLong()
                    } else null

                    variants.add(
                        VideoQualityOption(
                            quality = qualityLabel,
                            resolution = resStr,
                            bandwidth = bandwidth,
                            sizeBytes = estimatedBytes,
                            streamUrl = resolvedUrl,
                            format = VideoFormat.HLS
                        )
                    )
                }
            }
            i++
        }

        // Deduplicate and sort descending by vertical resolution then bandwidth
        // Deduplicated on the stream URL, not the label: a master playlist legitimately carries
        // several 1080p renditions (different codecs or bitrates) and dropping them by name threw
        // away real choices.
        val sortedVariants = variants
            .distinctBy { it.streamUrl }
            .sortedWith(
                compareByDescending<VideoQualityOption> { option ->
                    option.resolution?.substringAfter('x')?.toIntOrNull() ?: 0
                }.thenByDescending { it.bandwidth }
            )

        return ParsedHls(
            isMaster = true,
            variants = sortedVariants
        )
    }

    fun qualityNameForHeight(height: Int): String = when {
        height >= 2160 -> "4K (2160p)"
        height >= 1440 -> "2K (1440p)"
        height >= 1080 -> "1080p (Full HD)"
        height >= 720 -> "720p (HD)"
        height >= 480 -> "480p (SD)"
        height >= 360 -> "360p"
        height >= 240 -> "240p"
        else -> "${height}p"
    }

    fun resolveUrl(urlToResolve: String, baseUrl: String): String {
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(urlToResolve).toString()
        } catch (_: Exception) {
            when {
                urlToResolve.startsWith("http://") || urlToResolve.startsWith("https://") -> urlToResolve
                urlToResolve.startsWith("/") -> {
                    // substringBefore("/") stopped at the "//" in the scheme and produced
                    // "https:" as the origin.
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
}
