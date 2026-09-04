package com.scriptoria.browser.engine.adblock

import android.net.Uri
import jp.hazuki.yuzubrowser.adblock.core.ContentRequest
import jp.hazuki.yuzubrowser.adblock.core.FilterContainer
import jp.hazuki.yuzubrowser.adblock.filter.abp.AbpFilterDecoder
import jp.hazuki.yuzubrowser.adblock.filter.unified.STRICT_FIRST_PARTY
import jp.hazuki.yuzubrowser.adblock.filter.unified.THIRD_PARTY
import jp.hazuki.yuzubrowser.adblock.filter.unified.element.ElementContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterEngineTest {

    // --- block / allow precedence -------------------------------------------------------------

    @Test
    fun blocksMatchingRequestAndLeavesOthersAlone() {
        val engine = engineOf("||ads.example.com^")
        assertTrue(engine.blocks("https://ads.example.com/banner.js"))
        assertFalse(engine.blocks("https://cdn.example.com/app.js"))
    }

    @Test
    fun exceptionRuleOverridesBlockRule() {
        val engine = engineOf("||ads.example.com^", "@@||ads.example.com/allowed.js")
        assertTrue(engine.blocks("https://ads.example.com/banner.js"))
        assertFalse(engine.blocks("https://ads.example.com/allowed.js"))
    }

    @Test
    fun importantOutranksAnException() {
        val engine = engineOf("||ads.example.com^\$important", "@@||ads.example.com^")
        assertTrue(engine.blocks("https://ads.example.com/banner.js"))
    }

    @Test
    fun exceptionInOneListOverridesBlockInAnother() {
        // The tiers are checked across every list, so list order must not decide the outcome.
        val engine = FilterEngine(listOf(compile("||ads.example.com^"), compile("@@||ads.example.com^")))
        assertFalse(engine.blocks("https://ads.example.com/banner.js"))
    }

    @Test
    fun thirdPartyOptionOnlyMatchesOffSiteRequests() {
        val engine = engineOf("||track.example.com^\$third-party")
        assertTrue(engine.blocks("https://track.example.com/p.gif", pageHost = "news.test", party = THIRD_PARTY))
        assertFalse(engine.blocks("https://track.example.com/p.gif", pageHost = "track.example.com", party = STRICT_FIRST_PARTY))
    }

    @Test
    fun domainOptionLimitsRuleToNamedSites() {
        val engine = engineOf("||widget.example.com^\$domain=news.test")
        assertTrue(engine.blocks("https://widget.example.com/w.js", pageHost = "news.test"))
        assertFalse(engine.blocks("https://widget.example.com/w.js", pageHost = "other.test"))
    }

    // --- $redirect ----------------------------------------------------------------------------

    @Test
    fun redirectFilterNamesItsStubResource() {
        val engine = engineOf("||ads.example.com/ad.js\$redirect=noopjs")
        val request = requestFor("https://ads.example.com/ad.js")
        assertTrue(engine.shouldBlock(request))
        assertEquals("noopjs", engine.redirectName(request))
    }

    @Test
    fun emptyOptionBlocksAndServesTheEmptyStub() {
        val engine = engineOf("||ads.example.com/pixel\$empty")
        val request = requestFor("https://ads.example.com/pixel")
        assertTrue(engine.shouldBlock(request))
        assertEquals("empty", engine.redirectName(request))
    }

    @Test
    fun plainBlockHasNoRedirectResource() {
        val engine = engineOf("||ads.example.com^")
        assertNull(engine.redirectName(requestFor("https://ads.example.com/x.js")))
    }

    // --- $removeparam -------------------------------------------------------------------------

    @Test
    fun removeparamDropsOnlyTheNamedParameter() {
        val engine = engineOf("\$removeparam=utm_source")
        val kept = engine.keptParams("https://shop.test/p?id=7&utm_source=news&ref=a")
        assertEquals(listOf("id", "ref"), kept)
    }

    @Test
    fun removeparamWithNoValueDropsEveryParameter() {
        val engine = engineOf("||shop.test^\$removeparam")
        assertEquals(emptyList<String>(), engine.keptParams("https://shop.test/p?id=7&utm_source=news"))
    }

    @Test
    fun invertedRemoveparamKeepsOnlyTheNamedParameter() {
        val engine = engineOf("||shop.test^\$removeparam=~id")
        assertEquals(listOf("id"), engine.keptParams("https://shop.test/p?id=7&utm_source=news&ref=a"))
    }

    @Test
    fun urlWithNothingToStripIsLeftAlone() {
        val engine = engineOf("\$removeparam=utm_source")
        assertNull(engine.keptParams("https://shop.test/p?id=7"))
    }

    @Test
    fun noRemoveparamRulesLeavesUrlAlone() {
        val engine = engineOf("||ads.example.com^")
        assertNull(engine.keptParams("https://shop.test/p?utm_source=news"))
    }

    // --- cosmetic filtering -------------------------------------------------------------------

    @Test
    fun genericAndDomainRulesBothApply() {
        val engine = engineOf("##.ad-banner", "news.test##.sponsored")
        val selectors = engine.hidingSelectors(Uri.parse("https://news.test/story"))
        assertTrue(".ad-banner" in selectors)
        assertTrue(".sponsored" in selectors)
    }

    @Test
    fun domainRuleDoesNotLeakToOtherSites() {
        val engine = engineOf("news.test##.sponsored")
        assertFalse(".sponsored" in engine.hidingSelectors(Uri.parse("https://other.test/")))
    }

    @Test
    fun unhideRuleCancelsASelector() {
        val engine = engineOf("##.ad-banner", "news.test#@#.ad-banner")
        assertFalse(".ad-banner" in engine.hidingSelectors(Uri.parse("https://news.test/")))
        assertTrue(".ad-banner" in engine.hidingSelectors(Uri.parse("https://other.test/")))
    }

    @Test
    fun elemhideExceptionTurnsOffHidingForThePage() {
        val engine = engineOf("##.ad-banner", "@@||news.test^\$elemhide")
        assertEquals(emptyList<String>(), engine.hidingSelectors(Uri.parse("https://news.test/")))
    }

    @Test
    fun generichideKeepsDomainRulesButDropsGenericOnes() {
        val engine = engineOf("##.ad-banner", "news.test##.sponsored", "@@||news.test^\$generichide")
        val selectors = engine.hidingSelectors(Uri.parse("https://news.test/"))
        assertFalse(".ad-banner" in selectors)
        assertTrue(".sponsored" in selectors)
    }

    // --- scriptlets ---------------------------------------------------------------------------

    @Test
    fun scriptletAppliesToItsOwnDomain() {
        val engine = engineOf("news.test##+js(set-constant, adsBlocked, false)")
        assertEquals(
            listOf("set-constant, adsBlocked, false"),
            engine.scriptletCalls(Uri.parse("https://news.test/story")),
        )
    }

    @Test
    fun scriptletDoesNotLeakToOtherDomains() {
        val engine = engineOf("news.test##+js(nowebrtc)")
        assertTrue(engine.scriptletCalls(Uri.parse("https://other.test/")).isEmpty())
    }

    @Test
    fun scriptletIsNotTreatedAsAHidingSelector() {
        // A "+js(...)" body must never reach the stylesheet.
        val engine = engineOf("news.test##+js(nowebrtc)")
        assertTrue(engine.hidingSelectors(Uri.parse("https://news.test/")).isEmpty())
    }

    @Test
    fun hidingRuleIsNotTreatedAsAScriptlet() {
        val engine = engineOf("news.test##.ad-banner")
        assertTrue(engine.scriptletCalls(Uri.parse("https://news.test/")).isEmpty())
    }

    @Test
    fun scriptletExceptionCancelsIt() {
        val engine = engineOf("news.test##+js(nowebrtc)", "news.test#@#+js(nowebrtc)")
        assertTrue(engine.scriptletCalls(Uri.parse("https://news.test/")).isEmpty())
    }

    @Test
    fun elemhideExceptionAlsoSuppressesScriptlets() {
        val engine = engineOf("news.test##+js(nowebrtc)", "@@||news.test^\$elemhide")
        assertTrue(engine.scriptletCalls(Uri.parse("https://news.test/")).isEmpty())
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun engineOf(vararg lines: String) = FilterEngine(listOf(compile(*lines)))

    /** Runs filter text through the real decoder, so the tests exercise parsing as well. */
    private fun compile(vararg lines: String): FilterEngine.CompiledList {
        val text = (listOf("[Adblock Plus 2.0]") + lines).joinToString("\n")
        val decoder = AbpFilterDecoder()
        val set = text.reader().buffered().use { reader ->
            // checkHeader consumes the list header, exactly as the store does before decoding.
            assertTrue(decoder.checkHeader(reader, Charsets.UTF_8))
            decoder.decode(reader, null)
        }

        return FilterEngine.CompiledList(
            containers = FilterEngine.LOADED_PREFIXES.associateWith { prefix ->
                FilterContainer().also { container ->
                    set.filters[prefix].forEach { container += it }
                }
            },
            elements = ElementContainer().also { container ->
                set.elementList.forEach { container += it }
            },
            elementDisables = FilterContainer().also { container ->
                set.elementDisableFilter.forEach { container += it }
            },
            scriptlets = ElementContainer().also { container ->
                set.scriptletList.forEach { container += it }
            },
        )
    }

    private fun requestFor(
        url: String,
        pageHost: String? = "news.test",
        party: Int = THIRD_PARTY,
        type: Int = ContentRequest.TYPE_SCRIPT,
    ) = ContentRequest(Uri.parse(url), pageHost, type, party)

    private fun FilterEngine.blocks(
        url: String,
        pageHost: String? = "news.test",
        party: Int = THIRD_PARTY,
    ) = shouldBlock(requestFor(url, pageHost, party))

    private fun FilterEngine.keptParams(url: String): List<String>? {
        val uri = Uri.parse(url)
        val request = ContentRequest(uri, uri.host, ContentRequest.TYPE_DOCUMENT, STRICT_FIRST_PARTY)
        return keptQueryParams(request, uri.queryParameterNames.toList())
    }
}
