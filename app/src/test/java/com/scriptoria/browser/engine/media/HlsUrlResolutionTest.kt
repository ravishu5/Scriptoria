package com.scriptoria.browser.engine.media

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsUrlResolutionTest {

    @Test
    fun resolvesRelativeSegmentPaths() {
        assertEquals(
            "https://cdn.test/stream/720p/index.m3u8",
            HlsManifestParser.resolveUrl("720p/index.m3u8", "https://cdn.test/stream/master.m3u8"),
        )
    }

    @Test
    fun resolvesRootRelativePaths() {
        assertEquals(
            "https://cdn.test/hls/720p.m3u8",
            HlsManifestParser.resolveUrl("/hls/720p.m3u8", "https://cdn.test/stream/master.m3u8"),
        )
    }

    @Test
    fun leavesAbsoluteUrlsAlone() {
        assertEquals(
            "https://other.test/a.m3u8",
            HlsManifestParser.resolveUrl("https://other.test/a.m3u8", "https://cdn.test/master.m3u8"),
        )
    }

    @Test
    fun rootRelativePathKeepsTheWholeOrigin() {
        // The fallback used substringBefore("/"), which stopped inside "https://" and produced
        // "https:/hls/x.m3u8". URI.resolve handles the normal case; this pins the fallback.
        val base = "https://cdn.test:8443/a b/master.m3u8" // space makes URI() throw
        assertEquals(
            "https://cdn.test:8443/hls/x.m3u8",
            HlsManifestParser.resolveUrl("/hls/x.m3u8", base),
        )
    }
}
