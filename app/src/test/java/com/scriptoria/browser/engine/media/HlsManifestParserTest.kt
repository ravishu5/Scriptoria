package com.scriptoria.browser.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsManifestParserTest {

    private val sampleMasterPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-INDEPENDENT-SEGMENTS

        #EXT-X-STREAM-INF:BANDWIDTH=6000000,AVERAGE-BANDWIDTH=5500000,RESOLUTION=1920x1080,FRAME-RATE=60.000,CODECS="avc1.64002a,mp4a.40.2"
        1080p/index.m3u8

        #EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2800000,RESOLUTION=1280x720,FRAME-RATE=30.000,CODECS="avc1.4d401f,mp4a.40.2"
        720p/index.m3u8

        #EXT-X-STREAM-INF:BANDWIDTH=1500000,AVERAGE-BANDWIDTH=1400000,RESOLUTION=854x480,FRAME-RATE=30.000,CODECS="avc1.4d401e,mp4a.40.2"
        480p/index.m3u8

        #EXT-X-STREAM-INF:BANDWIDTH=800000,AVERAGE-BANDWIDTH=750000,RESOLUTION=640x360,FRAME-RATE=30.000,CODECS="avc1.4d401e,mp4a.40.2"
        https://cdn.example.com/stream/360p/index.m3u8
    """.trimIndent()

    private val sampleMediaPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:10.0,
        segment0.ts
        #EXTINF:10.0,
        segment1.ts
        #EXTINF:5.5,
        segment2.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun testParseMasterPlaylistExtractsVariants() {
        val baseUrl = "https://cdn.example.com/stream/master.m3u8"
        val parsed = HlsManifestParser.parse(
            manifestContent = sampleMasterPlaylist,
            baseUrl = baseUrl,
            totalDurationSeconds = 120.0
        )

        assertTrue("Should be detected as master playlist", parsed.isMaster)
        assertEquals(4, parsed.variants.size)

        // Variant 1: 1080p
        val v1 = parsed.variants[0]
        assertEquals("1080p (Full HD)", v1.quality)
        assertEquals("1920x1080", v1.resolution)
        assertEquals(6000000L, v1.bandwidth)
        assertEquals("https://cdn.example.com/stream/1080p/index.m3u8", v1.streamUrl)
        assertNotNull(v1.sizeBytes)
        // 6,000,000 bits/sec / 8 = 750,000 bytes/sec * 120 sec = 90,000,000 bytes (~90 MB)
        assertEquals(90000000L, v1.sizeBytes)
        assertTrue(v1.formattedSize?.contains("MB") == true)

        // Variant 2: 720p
        val v2 = parsed.variants[1]
        assertEquals("720p (HD)", v2.quality)
        assertEquals("1280x720", v2.resolution)
        assertEquals("https://cdn.example.com/stream/720p/index.m3u8", v2.streamUrl)

        // Variant 4: Absolute URL preservation
        val v4 = parsed.variants[3]
        assertEquals("360p", v4.quality)
        assertEquals("https://cdn.example.com/stream/360p/index.m3u8", v4.streamUrl)
    }

    @Test
    fun testParseMediaPlaylist() {
        val baseUrl = "https://cdn.example.com/stream/media.m3u8"
        val parsed = HlsManifestParser.parse(
            manifestContent = sampleMediaPlaylist,
            baseUrl = baseUrl
        )

        assertFalse("Should not be master playlist", parsed.isMaster)
        assertEquals(3, parsed.segmentsCount)
        assertEquals(1, parsed.variants.size)
        assertEquals("Auto / Source", parsed.variants[0].quality)
        assertEquals(baseUrl, parsed.variants[0].streamUrl)
    }

    @Test
    fun distinctRenditionsSharingALabelAreKept() {
        // Two 1080p renditions at different bitrates are a real choice; deduplicating on the
        // label threw one away.
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            1080p_high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
            1080p_low/index.m3u8
        """.trimIndent()
        val parsed = HlsManifestParser.parse(master, "https://cdn.test/stream/master.m3u8")
        assertEquals(2, parsed.variants.size)
        // Highest bandwidth first at equal resolution.
        assertEquals(6000000L, parsed.variants.first().bandwidth)
    }

    @Test
    fun mediaPlaylistDurationIsSummedFromSegments() {
        val parsed = HlsManifestParser.parse(sampleMediaPlaylist, "https://cdn.test/stream/index.m3u8")
        assertFalse(parsed.isMaster)
        assertEquals(3, parsed.segmentsCount)
        assertEquals(25.5, parsed.totalDurationSeconds, 0.001)
    }
}
