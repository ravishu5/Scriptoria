package com.scriptoria.browser.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the classification rules only. They are pure, so the manager is never asked to detect
 * anything and its HTTP client is never used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaClassificationTest {

    private val manager = VideoDetectionManager(httpClient = okhttp3.OkHttpClient())

    @Test
    fun recognisesStandaloneMediaFiles() {
        assertEquals(VideoFormat.MP4, manager.classifyFormat("https://cdn.test/clip.mp4"))
        assertEquals(VideoFormat.WEBM, manager.classifyFormat("https://cdn.test/clip.webm"))
        assertEquals(VideoFormat.HLS, manager.classifyFormat("https://cdn.test/master.m3u8"))
        assertEquals(VideoFormat.DASH, manager.classifyFormat("https://cdn.test/manifest.mpd"))
    }

    @Test
    fun segmentsAreNotStandaloneMedia() {
        // A stream has hundreds of these. Treating each as its own video filled the sheet with
        // junk and fired a manifest fetch per segment.
        assertNull(manager.classifyFormat("https://cdn.test/stream/segment0.ts"))
        assertNull(manager.classifyFormat("https://cdn.test/stream/segment12.m4s"))
        assertNull(manager.classifyFormat("https://cdn.test/seg/000123.ts?token=abc"))
    }

    @Test
    fun nonMediaIsIgnored() {
        assertNull(manager.classifyFormat("https://cdn.test/app.js"))
        assertNull(manager.classifyFormat("https://cdn.test/style.css"))
    }

    @Test
    fun adAndTrackingUrlsAreRejected() {
        assertTrue(manager.isAdOrTracking("https://doubleclick.net/preroll.mp4"))
        assertTrue(manager.isAdOrTracking("https://cdn.test/pixel.gif"))
    }

    @Test
    fun youTubePagesAreLeftToTheExtractor() {
        // Sniffing YouTube yields adaptive video-only/audio-only streams, which download to a
        // silent clip or a bare audio track.
        assertTrue(manager.usesExtractor("https://www.youtube.com/watch?v=abc"))
        assertTrue(manager.usesExtractor("https://m.youtube.com/watch?v=abc"))
        assertTrue(manager.usesExtractor("https://youtu.be/abc"))
    }

    @Test
    fun ordinarySitesAreStillSniffed() {
        assertFalse(manager.usesExtractor("https://news.test/watch"))
        assertFalse(manager.usesExtractor("https://notyoutube.com/watch"))
        assertFalse(manager.usesExtractor(null))
    }

    @Test
    fun segmentsUnderAFileNamedDirectoryAreNotVideos() {
        // The shape that flooded the sheet: the segment directory is named after the source file,
        // so ".mp4" appears in the middle of the URL while the request is really one .ts segment.
        assertNull(manager.classifyFormat("https://cdn.test/hls/clip.mp4/seg-1-v1-a1.ts"))
        assertNull(manager.classifyFormat("https://cdn.test/hls/clip.mp4/seg-42-v1-a1.ts?t=9"))
        assertNull(manager.classifyFormat("https://cdn.test/hls/clip.mp4/init-v1-a1.m4s"))
    }

    @Test
    fun theManifestBesideThoseSegmentsIsStillAVideo() {
        assertEquals(
            VideoFormat.HLS,
            manager.classifyFormat("https://cdn.test/hls/clip.mp4/index-v1-a1.m3u8"),
        )
    }

    @Test
    fun extensionOnlyCountsAtTheEndOfThePath() {
        assertEquals(VideoFormat.MP4, manager.classifyFormat("https://cdn.test/clip.mp4?token=x"))
        assertNull(manager.classifyFormat("https://cdn.test/player?poster=clip.mp4&id=3"))
    }

    @Test
    fun sidecarStreamPartsAreNotVideos() {
        assertNull(manager.classifyFormat("https://cdn.test/hls/audio-1.aac"))
        assertNull(manager.classifyFormat("https://cdn.test/hls/subs-1.vtt"))
        assertNull(manager.classifyFormat("https://cdn.test/hls/enc.key"))
    }
}
