package com.scriptoria.browser.engine.media

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectedMediaTest {

    private fun option(quality: String, resolution: String? = null, bandwidth: Long = 0L) =
        VideoQualityOption(
            quality = quality,
            resolution = resolution,
            bandwidth = bandwidth,
            streamUrl = "https://cdn.test/$quality.m3u8",
            format = VideoFormat.HLS,
        )

    private fun video(vararg qualities: VideoQualityOption) = DetectedVideo(
        id = "1",
        pageUrl = "https://news.test/watch",
        masterUrl = "https://cdn.test/master.m3u8",
        title = "Clip",
        format = VideoFormat.HLS,
        qualities = qualities.toList(),
    )

    @Test
    fun bitrateLabelDoesNotOutrankAResolution() {
        // "5000 kbps" scores 5000 when the label's digits are mashed together, beating 1080p.
        val best = video(
            option("1080p (Full HD)", resolution = "1920x1080"),
            option("5000 kbps", bandwidth = 5_000_000),
        ).bestQuality
        assertEquals("1080p (Full HD)", best?.quality)
    }

    @Test
    fun fourKOutranksFullHd() {
        // "4K (2160p)" and "2K (1440p)" read as 42160 and 21440 under digit-mashing, which happens
        // to order those two correctly but puts both far above everything else for the wrong reason.
        val best = video(
            option("1080p (Full HD)", resolution = "1920x1080"),
            option("4K (2160p)", resolution = "3840x2160"),
            option("720p (HD)", resolution = "1280x720"),
        ).bestQuality
        assertEquals("4K (2160p)", best?.quality)
    }

    @Test
    fun heightIsReadFromTheLabelWhenThereIsNoResolution() {
        assertEquals(720, option("720p (HD)").heightOrZero)
        assertEquals(0, option("Auto / Source").heightOrZero)
    }

    @Test
    fun bandwidthBreaksTiesAtEqualResolution() {
        val best = video(
            option("1080p", resolution = "1920x1080", bandwidth = 3_000_000),
            option("1080p", resolution = "1920x1080", bandwidth = 6_000_000),
        ).bestQuality
        assertEquals(6_000_000L, best?.bandwidth)
    }

    @Test
    fun sizesUseADotDecimalSeparatorRegardlessOfLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.5 MB", VideoQualityOption.formatBytes(1_572_864))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
