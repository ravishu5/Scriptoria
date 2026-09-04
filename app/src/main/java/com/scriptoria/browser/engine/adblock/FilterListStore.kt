package com.scriptoria.browser.engine.adblock

import android.content.Context
import android.util.Log
import com.scriptoria.browser.data.preferences.AdblockPreferences
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_DISABLE_ELEMENT_PAGE
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_ELEMENT
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_SCRIPTLET
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_BADFILTER
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_DENY
import jp.hazuki.yuzubrowser.adblock.filter.abp.AbpFilterDecoder
import jp.hazuki.yuzubrowser.adblock.filter.abp.blockerPrefixes
import jp.hazuki.yuzubrowser.adblock.filter.abp.isModify
import jp.hazuki.yuzubrowser.adblock.filter.unified.UnifiedFilter
import jp.hazuki.yuzubrowser.adblock.filter.unified.UnifiedFilterSet
import jp.hazuki.yuzubrowser.adblock.filter.unified.element.ElementFilter
import jp.hazuki.yuzubrowser.adblock.filter.unified.io.ElementWriter
import jp.hazuki.yuzubrowser.adblock.filter.unified.io.FilterWriter
import jp.hazuki.yuzubrowser.adblock.repository.abp.AbpEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads blocklists and compiles them into the engine's binary format.
 *
 * Parsing EasyList from text costs seconds; the binary form loads in a fraction of that, so text
 * is only ever touched on an update and every browser start reads the compiled files instead.
 */
class FilterListStore(
    context: Context,
    private val httpClient: OkHttpClient,
    private val preferences: AdblockPreferences,
) {

    val dir: File = File(context.filesDir, "adblock").apply { mkdirs() }

    /**
     * @return the number of lists whose compiled output changed, so the caller knows whether the
     * in-memory containers need rebuilding.
     */
    suspend fun refresh(force: Boolean): Int = withContext(Dispatchers.IO) {
        var updated = 0
        for (entity in preferences.getLists()) {
            if (!entity.enabled) continue
            if (!force && !entity.isExpired()) continue
            try {
                if (download(entity)) updated++
            } catch (e: Throwable) {
                // Throwable, not Exception: these lists are untrusted remote text, and a
                // pathological line can exhaust the parser's stack. A list that fails to refresh
                // keeps its previously compiled files, so blocking degrades to stale rules rather
                // than to nothing — and never to a dead browser.
                Log.w(TAG, "Failed to update ${entity.title}: $e")
            }
        }
        updated
    }

    /** True when every enabled list has compiled output on disk. */
    fun hasCompiledData(): Boolean = preferences.getLists()
        .filter { it.enabled }
        .all { entity -> blockerPrefixes.any { File(dir, it + entity.entityId).exists() } }

    private fun download(entity: AbpEntity): Boolean {
        val request = Request.Builder().url(entity.url).apply {
            // Some list hosts answer 403 to an unrecognised client, so identify the app rather
            // than letting the request go out as the HTTP library's default.
            header("User-Agent", USER_AGENT)
            // Skip the transfer entirely when the server says nothing changed.
            entity.lastModified?.let { header("If-Modified-Since", it) }
        }.build()

        // Lists run to several megabytes and the format is only known once the first line is read,
        // so the body lands in a temp file that can be read twice rather than being held in memory.
        val temp = File(dir, "${entity.entityId}.download")
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 304) return false
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty body")
                temp.outputStream().use { out -> body.byteStream().copyTo(out) }
                entity.lastModified = response.header("Last-Modified")
            }

            val decoder = AbpFilterDecoder()
            val isAbp = temp.reader().buffered().use { decoder.checkHeader(it, Charsets.UTF_8) }

            if (isAbp) {
                val set = temp.reader().buffered().use { reader ->
                    // checkHeader consumed part of the stream on the first pass, so re-run it on
                    // this reader to leave it positioned where decode expects.
                    decoder.checkHeader(reader, Charsets.UTF_8)
                    decoder.decode(reader, entity.url)
                }
                writeAbp(entity, set)
                set.filterInfo.let { info ->
                    if (entity.title.isNullOrBlank()) entity.title = info.title
                    info.expires?.let { entity.expires = it }
                    entity.version = info.version
                    entity.lastUpdate = info.lastUpdate
                }
            } else {
                val filters = temp.reader().buffered().use { HostsFileParser.parse(it) }
                if (filters.isEmpty()) throw IOException("not a recognised filter list")
                writeHosts(entity, filters)
            }

            entity.lastLocalUpdate = System.currentTimeMillis()
            preferences.updateList(entity)
            return true
        } finally {
            temp.delete()
        }
    }

    private fun writeAbp(entity: AbpEntity, set: UnifiedFilterSet) {
        for (prefix in blockerPrefixes) {
            // $badfilter lines cancel an identical rule from any list; UnifiedFilter defines
            // equality over pattern, type, domains and party, so a set subtraction is enough.
            val cancelled = set.filters[ABP_PREFIX_BADFILTER + prefix].toSet()
            val filters = set.filters[prefix].filterNot { it in cancelled }
            writeFilters(prefix + entity.entityId, filters, isModify(prefix))
        }
        writeElements(ABP_PREFIX_ELEMENT + entity.entityId, set.elementList)
        writeElements(ABP_PREFIX_SCRIPTLET + entity.entityId, set.scriptletList)
        writeFilters(
            ABP_PREFIX_DISABLE_ELEMENT_PAGE + entity.entityId,
            set.elementDisableFilter,
            modify = false,
        )
    }

    private fun writeHosts(entity: AbpEntity, filters: List<UnifiedFilter>) {
        // A hosts file only ever produces block rules; clear anything a previous ABP-format
        // version of the same list left behind.
        for (prefix in blockerPrefixes) {
            writeFilters(
                prefix + entity.entityId,
                if (prefix == ABP_PREFIX_DENY) filters else emptyList(),
                isModify(prefix),
            )
        }
        writeElements(ABP_PREFIX_ELEMENT + entity.entityId, emptyList())
        writeElements(ABP_PREFIX_SCRIPTLET + entity.entityId, emptyList())
        writeFilters(ABP_PREFIX_DISABLE_ELEMENT_PAGE + entity.entityId, emptyList(), modify = false)
    }

    private fun writeFilters(name: String, filters: List<UnifiedFilter>, modify: Boolean) {
        val file = File(dir, name)
        if (filters.isEmpty()) {
            file.delete()
            return
        }
        file.outputStream().buffered().use { os ->
            val writer = FilterWriter()
            if (modify) writer.writeModifyFilters(os, filters) else writer.write(os, filters)
        }
    }

    private fun writeElements(name: String, filters: List<ElementFilter>) {
        val file = File(dir, name)
        if (filters.isEmpty()) {
            file.delete()
            return
        }
        file.outputStream().buffered().use { os -> ElementWriter().write(os, filters) }
    }

    /** Lists carry an "! Expires: n days" header; fall back to a day when they don't. */
    private fun AbpEntity.isExpired(): Boolean {
        val validForHours = if (expires > 0) expires else DEFAULT_EXPIRY_HOURS
        val age = System.currentTimeMillis() - lastLocalUpdate
        return age > validForHours * 60L * 60L * 1000L
    }

    companion object {
        private const val TAG = "AdblockStore"
        private const val USER_AGENT = "Scriptoria/1.0.0 (Android; +filter-list-updater)"
        private const val DEFAULT_EXPIRY_HOURS = 24
    }
}
