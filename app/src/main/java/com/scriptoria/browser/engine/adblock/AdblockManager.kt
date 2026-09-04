package com.scriptoria.browser.engine.adblock

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.scriptoria.browser.data.preferences.AdblockPreferences
import jp.hazuki.yuzubrowser.adblock.EmptyInputStream
import jp.hazuki.yuzubrowser.adblock.PublicSuffixes
import jp.hazuki.yuzubrowser.adblock.core.AbpLoader
import jp.hazuki.yuzubrowser.adblock.core.ContentRequest
import jp.hazuki.yuzubrowser.adblock.core.FilterContainer
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_DISABLE_ELEMENT_PAGE
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_SCRIPTLET
import jp.hazuki.yuzubrowser.adblock.filter.unified.STRICT_FIRST_PARTY
import jp.hazuki.yuzubrowser.adblock.filter.unified.THIRD_PARTY
import jp.hazuki.yuzubrowser.adblock.filter.unified.FIRST_PARTY
import jp.hazuki.yuzubrowser.adblock.filter.unified.element.ElementContainer
import jp.hazuki.yuzubrowser.adblock.getContentType
import jp.hazuki.yuzubrowser.adblock.repository.abp.AbpEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the compiled filters and answers the questions WebView asks on the request path.
 *
 * WebView calls in from a background thread, once per subresource, and blocks the load until it
 * gets an answer, so everything on that path is in-memory and lock-free: the [FilterEngine] is
 * built off the request path and published through a volatile field.
 *
 * The matching rules themselves live in [FilterEngine]; this class is the Android side of it —
 * preferences, the allowlist, list loading, caching and WebResourceResponse.
 */
class AdblockManager(
    context: Context,
    httpClient: OkHttpClient,
    val preferences: AdblockPreferences,
) {

    val store = FilterListStore(context, httpClient, preferences)

    @Volatile
    private var engine: FilterEngine? = null

    private val blockedCounter = AtomicLong(0)

    /** Lets a load that has been superseded by a newer one drop its results instead of racing. */
    private val loadGeneration = AtomicLong(0)

    /**
     * Hiding rules depend only on the host, and a page asks for them once per frame — a news site
     * with a dozen ad iframes would otherwise rebuild the same ten-thousand-selector rule a dozen
     * times over. Keyed by host and bounded, since a page pulls in frames from a handful of hosts.
     */
    private val cssCache = hostCache()
    private val scriptletCache = hostCache()
    private val cacheLock = ReentrantLock()

    private fun hostCache() = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
            size > HOST_CACHE_SIZE
    }

    /** Total requests blocked since process start, for the settings screen. */
    val blockedCount: Long get() = blockedCounter.get()

    val isReady: Boolean get() = engine != null

    /** Reads the compiled filters into memory. Safe to call again after a list refresh. */
    suspend fun load() = withContext(Dispatchers.IO) {
        cacheLock.withLock {
            cssCache.clear()
            scriptletCache.clear()
        }
        val entities = preferences.getLists().filter { it.enabled }
        if (entities.isEmpty()) {
            engine = null
            return@withContext
        }

        // A reload already has working filters in memory, so it swaps them in one go rather than
        // publishing partial coverage; a cold start has nothing to lose and publishes each list
        // the moment it is ready, which is what stops the first page load going unprotected.
        val publishAsReady = engine == null
        val generation = loadGeneration.incrementAndGet()
        val ready = mutableListOf<FilterEngine.CompiledList>()

        coroutineScope {
            entities.map { entity ->
                async {
                    val compiled = compile(entity) ?: return@async
                    synchronized(ready) {
                        // A newer load started while this one was decoding; its results win.
                        if (loadGeneration.get() != generation) return@async
                        ready += compiled
                        if (publishAsReady) engine = FilterEngine(ready.toList())
                    }
                }
            }.awaitAll()
        }

        synchronized(ready) {
            if (loadGeneration.get() != generation) return@withContext
            // Every list failing is indistinguishable from having none, and an empty engine would
            // report the blocker as ready while letting everything through.
            engine = ready.toList().takeIf { it.isNotEmpty() }?.let(::FilterEngine)
        }
    }

    /** @return null when a list has no usable compiled output, so the others still load. */
    private fun compile(entity: AbpEntity): FilterEngine.CompiledList? = try {
        val loader = AbpLoader(store.dir, listOf(entity))
        FilterEngine.CompiledList(
            containers = FilterEngine.LOADED_PREFIXES.associateWith { prefix ->
                FilterContainer().also { container ->
                    loader.loadAll(prefix).forEach(container::addWithTag)
                }
            },
            elements = ElementContainer().also { container ->
                loader.loadAllElementFilter().forEach { container += it }
            },
            elementDisables = FilterContainer().also { container ->
                loader.loadAll(ABP_PREFIX_DISABLE_ELEMENT_PAGE).forEach(container::addWithTag)
            },
            scriptlets = ElementContainer().also { container ->
                loader.loadAllElementFilter(ABP_PREFIX_SCRIPTLET).forEach { container += it }
            },
        )
    } catch (e: Exception) {
        Log.w(TAG, "Skipping ${entity.title}: ${e.message}")
        null
    }

    /** Downloads any stale lists, then rebuilds the in-memory filters if anything changed. */
    suspend fun refreshLists(force: Boolean) {
        val updated = store.refresh(force)
        if (updated > 0 || engine == null) load()
    }

    /**
     * @return a response to serve instead of the request, or null to let it through.
     */
    fun intercept(request: WebResourceRequest, pageUrl: String?): WebResourceResponse? {
        if (!preferences.isEnabled) return null
        val engine = this.engine ?: return null

        val url = request.url
        val scheme = url.scheme
        if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return null

        val pageUri = pageUrl?.let(Uri::parse)
        val pageHost = pageUri?.host
        // The main document is what the allowlist is keyed on, and never blocking it also keeps a
        // bad filter from making a site unreachable.
        if (request.isForMainFrame && url == pageUri) return null
        if (preferences.isAllowlisted(pageHost)) return null

        val contentRequest = ContentRequest(
            url = url,
            pageHost = pageHost,
            type = request.getContentType(pageUri ?: url),
            isThirdParty = thirdParty(url.host, pageHost),
        )

        if (!engine.shouldBlock(contentRequest)) return null

        blockedCounter.incrementAndGet()
        // A $redirect filter asks for a stand-in body rather than a dead request; a name we have
        // no stub for falls back to the plain empty response.
        // A $redirect filter asks for a stand-in body rather than a dead request; a name we have
        // no stub for falls back to the plain empty response.
        return RedirectResources.responseFor(engine.redirectName(contentRequest))
            ?: blockedResponse()
    }

    /**
     * Strips tracking parameters a $removeparam filter names from a page being navigated to.
     *
     * Only top-level navigations: those can be re-issued with a clean URL, whereas rewriting a
     * subresource would mean fetching it ourselves and handing WebView the response, which trades
     * a tracking parameter for a second cookie jar to keep in sync.
     *
     * @return the cleaned URL, or null when nothing matched and the load should proceed untouched.
     */
    fun rewriteNavigation(url: String): String? {
        if (!preferences.isEnabled) return null
        val engine = this.engine ?: return null

        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        if (uri.encodedQuery.isNullOrEmpty()) return null
        if (preferences.isAllowlisted(uri.host)) return null

        val names = runCatching { uri.queryParameterNames.toList() }.getOrNull() ?: return null
        val request = ContentRequest(
            url = uri,
            pageHost = uri.host,
            type = ContentRequest.TYPE_DOCUMENT,
            isThirdParty = STRICT_FIRST_PARTY,
        )
        val kept = engine.keptQueryParams(request, names) ?: return null

        val rebuilt = uri.buildUpon().clearQuery().apply {
            for (name in kept) {
                for (value in uri.getQueryParameters(name)) appendQueryParameter(name, value)
            }
        }.build().toString()

        return rebuilt.takeIf { it != url }
    }

    /**
     * The scriptlet calls for this document, as the JSON the injected library expects.
     *
     * Returns "[]" rather than null so the page-side code has one shape to handle.
     */
    fun scriptletCalls(url: Uri): String {
        if (!preferences.isEnabled) return EMPTY_CALLS
        if (preferences.isAllowlisted(url.host)) return EMPTY_CALLS
        val engine = this.engine ?: return EMPTY_CALLS
        val host = url.host ?: return EMPTY_CALLS

        cacheLock.withLock { scriptletCache[host]?.let { return it } }

        val json = try {
            Scriptlets.toJson(engine.scriptletCalls(url))
        } catch (e: Exception) {
            Log.w(TAG, "Scriptlet lookup failed for ${'$'}host: ${'$'}{e.message}")
            EMPTY_CALLS
        }

        cacheLock.withLock { scriptletCache[host] = json }
        return json
    }

    /**
     * The CSS that hides this page's ad slots, as a single rule.
     *
     * Emitted as CSS rather than as the querySelectorAll/remove script the engine ships, because a
     * style rule applied at document-start hides a slot before it ever paints; removing nodes can
     * only run once they exist, which is what produces the collapse-after-load flicker.
     */
    fun cosmeticCss(url: Uri): String? {
        if (!preferences.isEnabled) return null
        if (preferences.isAllowlisted(url.host)) return null
        val engine = this.engine ?: return null
        val host = url.host ?: return null

        cacheLock.withLock {
            if (cssCache.containsKey(host)) return cssCache[host]?.takeIf { it.isNotEmpty() }
        }

        val css = try {
            engine.hidingSelectors(url)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = ",")
                ?.plus("{display:none!important}")
        } catch (e: Exception) {
            Log.w(TAG, "Cosmetic filtering failed for $host: ${e.message}")
            null
        }

        // Cached as "" rather than skipped, so a host with no rules is not recomputed every frame.
        cacheLock.withLock { cssCache[host] = css ?: "" }
        return css
    }

    /**
     * An empty 200 rather than null: WebView treats a null response as "load it yourself", and an
     * error status makes some sites retry in a loop or show their own blocked-content notice.
     */
    private fun blockedResponse() = WebResourceResponse("text/plain", "utf-8", EmptyInputStream())

    private fun thirdParty(host: String?, pageHost: String?): Int {
        if (host == null || pageHost == null) return THIRD_PARTY
        if (host == pageHost) return STRICT_FIRST_PARTY
        val a = PublicSuffixes.effectiveTldPlusOne(host) ?: return THIRD_PARTY
        val b = PublicSuffixes.effectiveTldPlusOne(pageHost) ?: return THIRD_PARTY
        return if (a == b) FIRST_PARTY else THIRD_PARTY
    }

    companion object {
        private const val TAG = "AdblockManager"
        private const val HOST_CACHE_SIZE = 16
        private const val EMPTY_CALLS = "[]"
    }
}
