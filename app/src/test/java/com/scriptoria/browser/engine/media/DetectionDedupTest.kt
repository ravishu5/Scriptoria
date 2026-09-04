package com.scriptoria.browser.engine.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetectionDedupTest {

    /**
     * Progressive formats reported by a page extractor. Only exercises the extractor path, which
     * needs no network — the HLS path would try to fetch the manifest.
     */
    private fun formatsJson(videoId: String, vararg heights: Int): String {
        val formats = heights.joinToString(",") { h ->
            """{"url":"https://cdn.test/$videoId-$h.mp4","qualityLabel":"${h}p",""" +
                """"width":${h * 16 / 9},"height":$h,"bitrate":1000,"contentLength":"12345"}"""
        }
        return """{"videoId":"$videoId","title":"Clip","durationSeconds":60,"formats":[$formats]}"""
    }

    @Test
    fun extractedFormatsBecomeOneVideoWithManyQualities() = runTest {
        val manager = VideoDetectionManager(OkHttpClient(), this)
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", formatsJson("aaa", 720, 360))
        advanceUntilIdle()

        val videos = manager.getVideosForTab("t1")
        // One card, two qualities — not one card per stream.
        assertEquals(1, videos.size)
        assertEquals(2, videos.first().qualities.size)
        assertEquals("720p (HD)", videos.first().bestQuality?.quality)
    }

    @Test
    fun theSameVideoIsNotAddedTwice() = runTest {
        val manager = VideoDetectionManager(OkHttpClient(), this)
        val json = formatsJson("aaa", 360)
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", json)
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", json)
        advanceUntilIdle()

        assertEquals(1, manager.getVideosForTab("t1").size)
    }

    @Test
    fun differentVideosOnTheSameSiteStaySeparate() = runTest {
        val manager = VideoDetectionManager(OkHttpClient(), this)
        // Keyed on the video id, not the watch URL: stripping the query would make every video
        // on the site collapse into "https://www.youtube.com/watch".
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", formatsJson("aaa", 360))
        advanceUntilIdle()
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=bbb", formatsJson("bbb", 360))
        advanceUntilIdle()

        assertEquals(2, manager.getVideosForTab("t1").size)
    }

    @Test
    fun clearingATabDropsItsVideos() = runTest {
        val manager = VideoDetectionManager(OkHttpClient(), this)
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", formatsJson("aaa", 360))
        advanceUntilIdle()
        manager.clearTab("t1")
        assertEquals(0, manager.getVideosForTab("t1").size)
    }

    /** Progressive plus adaptive, the shape YouTube's player response actually has. */
    private fun youTubeJson(): String = """
        {
          "videoId":"aaa","title":"Clip","durationSeconds":60,
          "formats":[
            {"url":"https://cdn.test/prog360.mp4","qualityLabel":"360p","width":640,"height":360,
             "bitrate":500000,"contentLength":"1000","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\""}
          ],
          "adaptive":[
            {"url":"https://cdn.test/v1080.mp4","qualityLabel":"1080p","width":1920,"height":1080,
             "bitrate":4000000,"contentLength":"8000","mimeType":"video/mp4; codecs=\"avc1.640028\""},
            {"url":"https://cdn.test/v240.mp4","qualityLabel":"240p","width":426,"height":240,
             "bitrate":200000,"contentLength":"500","mimeType":"video/mp4; codecs=\"avc1.4d400c\""},
            {"url":"https://cdn.test/vp9.webm","qualityLabel":"1440p","width":2560,"height":1440,
             "bitrate":8000000,"contentLength":"9000","mimeType":"video/webm; codecs=\"vp9\""},
            {"url":"https://cdn.test/a128.m4a","qualityLabel":"","width":0,"height":0,
             "bitrate":128000,"contentLength":"2000","mimeType":"audio/mp4; codecs=\"mp4a.40.2\""},
            {"url":"https://cdn.test/opus.webm","qualityLabel":"","width":0,"height":0,
             "bitrate":160000,"contentLength":"2500","mimeType":"audio/webm; codecs=\"opus\""}
          ]
        }
    """.trimIndent()

    @Test
    fun adaptiveVideoIsPairedWithAacAudio() = runTest {
        val manager = VideoDetectionManager(OkHttpClient(), this)
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", youTubeJson())
        advanceUntilIdle()

        val q = manager.getVideosForTab("t1").single().qualities
        val hd = q.first { it.heightOrZero == 1080 }
        // Paired with the AAC track, not the higher-bitrate Opus one: MP4 cannot reliably hold
        // Opus across API levels.
        assertEquals("https://cdn.test/a128.m4a", hd.audioStreamUrl)
        assertTrue(hd.needsMuxing)
        // Size covers both downloads.
        assertEquals(10_000L, hd.sizeBytes)
    }

    @Test
    fun progressiveRenditionsNeedNoMuxing() = runTest {
        val manager = VideoDetectionManager(OkHttpClient(), this)
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", youTubeJson())
        advanceUntilIdle()

        val q = manager.getVideosForTab("t1").single().qualities
        assertFalse(q.first { it.heightOrZero == 360 }.needsMuxing)
    }

    @Test
    fun adaptiveBelowTheProgressiveCeilingIsNotOffered() = runTest {
        val manager = VideoDetectionManager(OkHttpClient(), this)
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", youTubeJson())
        advanceUntilIdle()

        val heights = manager.getVideosForTab("t1").single().qualities.map { it.heightOrZero }
        // 240p adaptive costs a second download and a rewrap to beat nothing.
        assertFalse(heights.contains(240))
        // The webm/VP9 rendition is skipped: it would not reliably rewrap into MP4.
        assertFalse(heights.contains(1440))
        assertEquals(listOf(1080, 360), heights)
    }

    @Test
    fun resultsFromThePageJustLeftAreDropped() = runTest {
        val manager = VideoDetectionManager(OkHttpClient(), this)
        // Detection is queued, then the user navigates before it commits.
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", formatsJson("aaa", 360))
        manager.clearTab("t1")
        advanceUntilIdle()

        // The old page's video must not reappear in the new page's list.
        assertEquals(0, manager.getVideosForTab("t1").size)
    }

    @Test
    fun detectionAfterANavigationStillLands() = runTest {
        val manager = VideoDetectionManager(OkHttpClient(), this)
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", formatsJson("aaa", 360))
        advanceUntilIdle()
        manager.clearTab("t1")

        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=bbb", formatsJson("bbb", 720))
        advanceUntilIdle()

        val videos = manager.getVideosForTab("t1")
        assertEquals(1, videos.size)
        assertEquals("extracted://bbb", videos.single().masterUrl)
    }

    @Test
    fun clearingOneTabLeavesOthersAlone() = runTest {
        val manager = VideoDetectionManager(OkHttpClient(), this)
        manager.onExtractedFormats("t1", "https://www.youtube.com/watch?v=aaa", formatsJson("aaa", 360))
        manager.onExtractedFormats("t2", "https://www.youtube.com/watch?v=bbb", formatsJson("bbb", 360))
        advanceUntilIdle()
        manager.clearTab("t1")

        assertEquals(0, manager.getVideosForTab("t1").size)
        assertEquals(1, manager.getVideosForTab("t2").size)
    }
}
