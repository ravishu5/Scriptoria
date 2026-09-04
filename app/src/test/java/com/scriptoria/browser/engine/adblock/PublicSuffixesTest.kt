package com.scriptoria.browser.engine.adblock

import jp.hazuki.yuzubrowser.adblock.PublicSuffixes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublicSuffixesTest {

    /**
     * The suffix list is reached through okhttp3.internal, a package with no compatibility
     * promise. If an OkHttp upgrade moves it, third-party detection quietly degrades to a
     * two-label guess — so this asserts the real list is still wired up rather than the fallback.
     */
    @Test
    fun okHttpSuffixListIsStillReachable() {
        assertTrue(
            "OkHttp's public suffix list is no longer reachable; " +
                "third-party detection has fallen back to the naive rule",
            PublicSuffixes.isAvailable(),
        )
    }

    @Test
    fun resolvesTheRegistrableDomain() {
        assertEquals("example.com", PublicSuffixes.effectiveTldPlusOne("cdn.example.com"))
        assertEquals("example.com", PublicSuffixes.effectiveTldPlusOne("example.com"))
    }

    @Test
    fun handlesMultiLabelSuffixes() {
        // The case the fallback gets wrong, and the reason the real list is worth depending on.
        assertEquals("example.co.uk", PublicSuffixes.effectiveTldPlusOne("www.example.co.uk"))
    }

    @Test
    fun aBareSuffixHasNoRegistrableDomain() {
        assertNull(PublicSuffixes.effectiveTldPlusOne("com"))
    }
}
