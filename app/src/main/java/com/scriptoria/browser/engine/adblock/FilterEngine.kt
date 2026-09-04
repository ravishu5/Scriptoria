package com.scriptoria.browser.engine.adblock

import android.net.Uri
import jp.hazuki.yuzubrowser.adblock.core.ContentRequest
import jp.hazuki.yuzubrowser.adblock.core.FilterContainer
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_ALLOW
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_DENY
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_IMPORTANT
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_IMPORTANT_ALLOW
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_MODIFY
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_MODIFY_EXCEPTION
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_REDIRECT
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_REDIRECT_EXCEPTION
import jp.hazuki.yuzubrowser.adblock.filter.unified.RedirectFilter
import jp.hazuki.yuzubrowser.adblock.filter.unified.RemoveparamFilter
import jp.hazuki.yuzubrowser.adblock.filter.unified.RemoveparamRegexFilter
import jp.hazuki.yuzubrowser.adblock.filter.unified.element.ElementContainer

/**
 * The filter-matching rules, with no Android plumbing attached.
 *
 * Everything here is a pure function of the compiled filters and the request, which is what makes
 * the precedence rules testable — they are the part most easily got subtly wrong, and the part a
 * user notices only as a site that mysteriously breaks.
 *
 * Instances are immutable once built and are read from WebView's background threads.
 */
class FilterEngine(private val lists: List<CompiledList>) {

    /** One filter list's compiled buckets. */
    class CompiledList(
        val containers: Map<String, FilterContainer>,
        val elements: ElementContainer,
        /** Element-hide exceptions: #@# rules and the $elemhide / $generichide options. */
        val elementDisables: FilterContainer,
        /** "+js(...)" rules, holding the call text where an element rule holds a selector. */
        val scriptlets: ElementContainer,
    )

    val isEmpty: Boolean get() = lists.isEmpty()

    /**
     * Precedence runs across every list at once, not list by list: an exception in EasyList has to
     * override a block rule in EasyPrivacy, so each tier is checked in all lists before the next.
     */
    fun shouldBlock(request: ContentRequest): Boolean {
        if (matchesAny(ABP_PREFIX_IMPORTANT_ALLOW, request)) return false
        if (matchesAny(ABP_PREFIX_IMPORTANT, request)) return true
        if (matchesAny(ABP_PREFIX_ALLOW, request)) return false
        return matchesAny(ABP_PREFIX_DENY, request)
    }

    /**
     * The stand-in resource a $redirect filter asks for, if any.
     *
     * $redirect writes both a block rule and a redirect rule, so this only decides what body to
     * serve for a request that is already going to be blocked.
     */
    fun redirectName(request: ContentRequest): String? {
        val exceptions = collect(ABP_PREFIX_REDIRECT_EXCEPTION, request) {
            (it as? RedirectFilter)?.parameter
        }.toSet()
        return collect(ABP_PREFIX_REDIRECT, request) { (it as? RedirectFilter)?.parameter }
            .firstOrNull { it !in exceptions }
    }

    /**
     * The query parameters of [request] that survive the $removeparam rules, in their original
     * order, or null when nothing applies and the URL should be left exactly as it is.
     */
    fun keptQueryParams(request: ContentRequest, names: List<String>): List<String>? {
        if (names.isEmpty()) return null

        val filters = collect(ABP_PREFIX_MODIFY, request) { it as? RemoveparamFilter }
        if (filters.isEmpty()) return null

        val exceptions = collect(ABP_PREFIX_MODIFY_EXCEPTION, request) { it as? RemoveparamFilter }
        // An exception with no parameter disables $removeparam for this URL outright; one naming a
        // parameter only cancels the rule for that parameter.
        if (exceptions.any { it.parameter == null }) return null
        val exempt = exceptions.mapNotNull { it.parameter }.toSet()

        val kept = names.filter { name ->
            name in exempt || filters.none { it.removes(name) }
        }
        return kept.takeIf { it.size != names.size }
    }

    /**
     * The selectors to hide on [url], or an empty list when the page has no rules or an
     * $elemhide exception turns cosmetic filtering off for it.
     */
    fun hidingSelectors(url: Uri): List<String> {
        val request = ContentRequest(url, url.host, ELEMENT_HIDE_TYPES, FIRST_PARTY_REQUEST)
        // The option is carried on the filter's contentType, not its filterType — filterType says
        // how the pattern is matched (host, prefix, regex), and comparing it here silently made
        // every $elemhide exception a no-op.
        val disables = lists.flatMap { it.elementDisables.getAll(request) }.map { it.contentType }
        // An exception in any list applies to all of them, so the widest one wins.
        val useGeneric = when {
            disables.any { it and ContentRequest.TYPE_ELEMENT_HIDE != 0 } -> return emptyList()
            disables.any { it and ContentRequest.TYPE_ELEMENT_GENERIC_HIDE != 0 } -> false
            else -> true
        }

        val matches = lists.flatMap { it.elements[url, useGeneric] }
        if (matches.isEmpty()) return emptyList()

        // An unhide rule (#@#) cancels the identical selector from every list.
        val unhidden = matches.asSequence().filterNot { it.isHide }.map { it.selector }.toSet()
        return matches.asSequence()
            .filter { it.isHide && it.selector !in unhidden }
            .map { it.selector }
            .distinct()
            .toList()
    }

    /**
     * The scriptlet calls that apply to [url], as raw "name, arg, arg" text.
     *
     * An $elemhide exception disables these too: it is the filter lists' way of saying a site
     * breaks under cosmetic intervention, and a scriptlet intervenes far more than a CSS rule.
     */
    fun scriptletCalls(url: Uri): List<String> {
        val request = ContentRequest(url, url.host, ELEMENT_HIDE_TYPES, FIRST_PARTY_REQUEST)
        val disabled = lists
            .flatMap { it.elementDisables.getAll(request) }
            .any { it.contentType and ContentRequest.TYPE_ELEMENT_HIDE != 0 }
        if (disabled) return emptyList()

        // Scriptlets are always domain-scoped in practice, so generic rules stay excluded.
        val matches = lists.flatMap { it.scriptlets[url, false] }
        val cancelled = matches.asSequence().filterNot { it.isHide }.map { it.selector }.toSet()
        return matches.asSequence()
            .filter { it.isHide && it.selector !in cancelled }
            .map { it.selector }
            .distinct()
            .toList()
    }

    private fun matchesAny(prefix: String, request: ContentRequest): Boolean =
        lists.any { it.containers[prefix]?.get(request) != null }

    private fun <T : Any> collect(
        prefix: String,
        request: ContentRequest,
        select: (jp.hazuki.yuzubrowser.adblock.filter.unified.ModifyFilter?) -> T?,
    ): List<T> = lists.flatMap { list ->
        list.containers[prefix]?.getAll(request).orEmpty().mapNotNull { select(it.modify) }
    }

    private fun RemoveparamFilter.removes(name: String): Boolean {
        val matched = when {
            // "$removeparam" with no value strips the query string entirely.
            parameter == null -> true
            this is RemoveparamRegexFilter -> regex.containsMatchIn(name)
            else -> parameter == name
        }
        // "~" inverts the rule: keep the named parameter, drop everything else.
        return if (inverse) !matched else matched
    }

    companion object {
        /** Buckets read on the request path; the rest are compiled but never consulted. */
        val LOADED_PREFIXES = listOf(
            ABP_PREFIX_ALLOW,
            ABP_PREFIX_DENY,
            ABP_PREFIX_IMPORTANT,
            ABP_PREFIX_IMPORTANT_ALLOW,
            ABP_PREFIX_REDIRECT,
            ABP_PREFIX_REDIRECT_EXCEPTION,
            ABP_PREFIX_MODIFY,
            ABP_PREFIX_MODIFY_EXCEPTION,
        )

        private const val ELEMENT_HIDE_TYPES =
            ContentRequest.TYPE_ELEMENT_HIDE or ContentRequest.TYPE_ELEMENT_GENERIC_HIDE

        private const val FIRST_PARTY_REQUEST =
            jp.hazuki.yuzubrowser.adblock.filter.unified.FIRST_PARTY
    }
}
