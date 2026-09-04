package com.scriptoria.browser.engine.adblock

import android.net.Uri
import jp.hazuki.yuzubrowser.adblock.core.ContentRequest
import jp.hazuki.yuzubrowser.adblock.filter.unified.THIRD_PARTY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostsFileParserTest {

    private fun parse(text: String) = HostsFileParser.parse(text.reader().buffered())

    private fun matches(text: String, url: String): Boolean {
        val request = ContentRequest(
            Uri.parse(url), "news.test", ContentRequest.TYPE_SCRIPT, THIRD_PARTY,
        )
        return parse(text).any { it.isMatch(request) }
    }

    @Test
    fun blocksHostsPointedAtABlackHoleAddress() {
        val hosts = "0.0.0.0 ads.example.com\n127.0.0.1 track.example.com\n"
        assertTrue(matches(hosts, "https://ads.example.com/a.js"))
        assertTrue(matches(hosts, "https://track.example.com/b.js"))
    }

    @Test
    fun matchesTheExactHostOnly() {
        // Hosts files name exact FQDNs; widening them would block names their authors left out.
        val hosts = "0.0.0.0 ads.example.com\n"
        assertFalse(matches(hosts, "https://cdn.ads.example.com/a.js"))
        assertFalse(matches(hosts, "https://example.com/a.js"))
    }

    @Test
    fun ignoresCommentsBlankLinesAndLoopbackEntries() {
        val hosts = """
            # This is a comment
            127.0.0.1 localhost
            ::1 ip6-localhost
            255.255.255.255 broadcasthost

            0.0.0.0 ads.example.com # trailing comment
        """.trimIndent()
        assertEquals(1, parse(hosts).size)
        assertTrue(matches(hosts, "https://ads.example.com/a.js"))
    }

    @Test
    fun readsEveryHostOnALineAndDropsDuplicates() {
        val hosts = "0.0.0.0 a.example.com b.example.com\n0.0.0.0 a.example.com\n"
        assertEquals(2, parse(hosts).size)
    }

    @Test
    fun ignoresLinesThatAreNotHostEntries() {
        // An ABP-syntax line must not be mistaken for a hosts entry.
        assertEquals(0, parse("||ads.example.com^\n! comment\n").size)
    }

    @Test
    fun ignoresRealAddressMappings() {
        // Only black-hole addresses mean "block"; a genuine mapping is not a filter.
        assertEquals(0, parse("93.184.216.34 example.com\n").size)
    }
}
