package com.scriptoria.browser.engine.matcher

import java.util.concurrent.ConcurrentHashMap

/**
 * URL Matching Engine supporting Tampermonkey & Violentmonkey specifications:
 * - `@match` adhering to Google Chrome match pattern grammar
 * - `@include` supporting glob patterns and `/regex/` literals
 * - `@exclude` & `@exclude-match` overriding inclusions
 *
 * Utilizes a thread-safe regex cache to ensure near-zero overhead across thousands of evaluations.
 */
object UrlMatcher {

    private val matchCache = ConcurrentHashMap<String, Regex?>()
    private val includeCache = ConcurrentHashMap<String, Regex?>()

    /**
     * Determines whether the given [url] matches the script rules.
     * Returns true if matched and not excluded.
     */
    fun matches(
        url: String,
        matches: List<String>,
        includes: List<String>,
        excludes: List<String>
    ): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
            return false
        }

        // Excludes always take precedence
        if (isExcluded(url, excludes)) {
            return false
        }

        // If neither match nor include is specified, it does not match
        if (matches.isEmpty() && includes.isEmpty()) {
            return false
        }

        return matches.any { testMatchPattern(url, it) } ||
                includes.any { testIncludePattern(url, it) }
    }

    fun isExcluded(url: String, excludes: List<String>): Boolean {
        if (excludes.isEmpty()) return false
        return excludes.any { testExcludePattern(url, it) }
    }

    private fun testExcludePattern(url: String, pattern: String): Boolean {
        val trimmed = pattern.trim()
        return testMatchPattern(url, trimmed) || testIncludePattern(url, trimmed)
    }

    private fun testMatchPattern(url: String, pattern: String): Boolean {
        val p = pattern.trim()
        if (p == "<all_urls>" || p == "*") {
            return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")
        }

        val regex = matchCache.computeIfAbsent(p) { compileMatchPattern(it) } ?: return false
        return regex.matches(url)
    }

    private fun testIncludePattern(url: String, pattern: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false

        // Regex literal: /regex/ or /regex/i
        if (p.length >= 2 && p.startsWith("/")) {
            val lastSlash = p.lastIndexOf('/')
            if (lastSlash > 0) {
                val regexPattern = p.substring(1, lastSlash)
                val flags = p.substring(lastSlash + 1)
                val regex = includeCache.computeIfAbsent(p) {
                    try {
                        val options = mutableSetOf<RegexOption>()
                        if ('i' in flags) options.add(RegexOption.IGNORE_CASE)
                        if ('s' in flags) options.add(RegexOption.DOT_MATCHES_ALL)
                        Regex(regexPattern, options)
                    } catch (e: Exception) {
                        null
                    }
                }
                return regex?.containsMatchIn(url) == true
            }
        }

        // Glob pattern
        val regex = includeCache.computeIfAbsent(p) { compileGlob(it) } ?: return false
        return regex.matches(url)
    }

    /**
     * Compiles Chrome Match Pattern grammar:
     * `<scheme>://<host><path>`
     */
    private fun compileMatchPattern(pattern: String): Regex? {
        val schemeSep = pattern.indexOf("://")
        if (schemeSep < 0) return null

        val scheme = pattern.substring(0, schemeSep)
        val rest = pattern.substring(schemeSep + 3)
        val slashIndex = rest.indexOf('/')
        if (slashIndex < 0) return null

        val host = rest.substring(0, slashIndex)
        val path = rest.substring(slashIndex)

        val schemeRegex = when (scheme) {
            "*" -> "https?"
            "http", "https", "file", "ftp" -> Regex.escape(scheme)
            else -> return null
        }

        val hostRegex = when {
            host == "*" -> "[^/]+"
            host.startsWith("*.") -> {
                val domain = host.substring(2)
                "(?:[^/]+\\.)?" + Regex.escape(domain)
            }
            else -> Regex.escape(host)
        }

        // Port tolerant: :port after host
        val pathRegex = globToRegexBody(path)
        return try {
            Regex("^$schemeRegex://$hostRegex(?::\\d+)?$pathRegex$")
        } catch (e: Exception) {
            null
        }
    }

    private fun compileGlob(glob: String): Regex? {
        val body = globToRegexBody(glob)
        return try {
            Regex("^$body$")
        } catch (e: Exception) {
            null
        }
    }

    private fun globToRegexBody(glob: String): String {
        return glob.split('*').joinToString(".*") { Regex.escape(it) }
    }

    fun clearCache() {
        matchCache.clear()
        includeCache.clear()
    }
}
