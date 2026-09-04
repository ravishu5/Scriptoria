package com.scriptoria.browser.engine.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedirectResourcesTest {

    @Test
    fun servesEachStubWithItsOwnMimeType() {
        assertEquals("application/javascript", RedirectResources.responseFor("noopjs")?.mimeType)
        assertEquals("image/gif", RedirectResources.responseFor("1x1.gif")?.mimeType)
        assertEquals("video/mp4", RedirectResources.responseFor("noopmp4-1s")?.mimeType)
        assertEquals("text/plain", RedirectResources.responseFor("empty")?.mimeType)
    }

    @Test
    fun aliasesResolveToTheSameStub() {
        assertNotNull(RedirectResources.responseFor("1x1-transparent.gif"))
        assertNotNull(RedirectResources.responseFor("noop.js"))
    }

    @Test
    fun cacheHintSuffixIsIgnored() {
        // Lists write these as "noopjs:5" to suggest a cache lifetime.
        assertEquals("application/javascript", RedirectResources.responseFor("noopjs:5")?.mimeType)
    }

    @Test
    fun unknownAndMissingNamesHaveNoStub() {
        // uBlock's behavioural surrogates are not bundled; they must fall back to a plain block.
        assertNull(RedirectResources.responseFor("google-analytics_analytics.js"))
        assertNull(RedirectResources.responseFor(null))
    }

    @Test
    fun imageStubsCarryRealDecodableBytes() {
        val gif = RedirectResources.responseFor("1x1.gif")!!.data.readBytes()
        assertTrue(gif.size > 20)
        assertEquals("GIF89a", String(gif.copyOfRange(0, 6)))

        val png = RedirectResources.responseFor("2x2.png")!!.data.readBytes()
        assertEquals(0x89.toByte(), png[0])
        assertEquals("PNG", String(png.copyOfRange(1, 4)))
    }

    @Test
    fun mp4StubIsAWellFormedContainer() {
        val mp4 = RedirectResources.responseFor("noopmp4-1s")!!.data.readBytes()
        // Box layout is <4-byte size><4-byte type>; a player rejects anything else outright.
        assertEquals("ftyp", String(mp4.copyOfRange(4, 8)))
        val ftypSize = ((mp4[0].toInt() and 0xff) shl 24) or ((mp4[1].toInt() and 0xff) shl 16) or
            ((mp4[2].toInt() and 0xff) shl 8) or (mp4[3].toInt() and 0xff)
        assertEquals("moov", String(mp4.copyOfRange(ftypSize + 4, ftypSize + 8)))
    }
}
