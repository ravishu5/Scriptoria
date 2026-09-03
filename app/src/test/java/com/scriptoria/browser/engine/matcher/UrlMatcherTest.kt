package com.scriptoria.browser.engine.matcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UrlMatcherTest {

    @Before
    fun setUp() {
        UrlMatcher.clearCache()
    }

    @Test
    fun testExactDomainAndWildcardPath() {
        val matches = listOf("https://example.com/*")
        val includes = emptyList<String>()
        val excludes = emptyList<String>()

        assertTrue(UrlMatcher.matches("https://example.com/", matches, includes, excludes))
        assertTrue(UrlMatcher.matches("https://example.com/path/to/page", matches, includes, excludes))
        assertTrue(UrlMatcher.matches("https://example.com/search?query=antigravity", matches, includes, excludes))

        // Different scheme or domain
        assertFalse(UrlMatcher.matches("http://example.com/", matches, includes, excludes))
        assertFalse(UrlMatcher.matches("https://other.com/", matches, includes, excludes))
    }

    @Test
    fun testWildcardDomain() {
        val matches = listOf("https://*.example.com/*")
        val includes = emptyList<String>()
        val excludes = emptyList<String>()

        // Subdomains
        assertTrue(UrlMatcher.matches("https://sub.example.com/page", matches, includes, excludes))
        assertTrue(UrlMatcher.matches("https://deep.nested.sub.example.com/page", matches, includes, excludes))
        // Base domain is also matched by *.domain per Chrome match pattern spec
        assertTrue(UrlMatcher.matches("https://example.com/page", matches, includes, excludes))

        // Different root domain
        assertFalse(UrlMatcher.matches("https://example.org/page", matches, includes, excludes))
    }

    @Test
    fun testWildcardScheme() {
        val matches = listOf("*://example.com/*")
        val includes = emptyList<String>()
        val excludes = emptyList<String>()

        assertTrue(UrlMatcher.matches("http://example.com/index.html", matches, includes, excludes))
        assertTrue(UrlMatcher.matches("https://example.com/index.html", matches, includes, excludes))
        assertFalse(UrlMatcher.matches("ftp://example.com/index.html", matches, includes, excludes))
    }

    @Test
    fun testExcludesOverrideMatches() {
        val matches = listOf("https://example.com/*")
        val includes = emptyList<String>()
        val excludes = listOf("https://example.com/private/*", "https://example.com/login")

        assertTrue(UrlMatcher.matches("https://example.com/public", matches, includes, excludes))
        assertFalse(UrlMatcher.matches("https://example.com/private/dashboard", matches, includes, excludes))
        assertFalse(UrlMatcher.matches("https://example.com/login", matches, includes, excludes))
    }

    @Test
    fun testPortTolerance() {
        val matches = listOf("https://example.com/*")
        val includes = emptyList<String>()
        val excludes = emptyList<String>()

        assertTrue(UrlMatcher.matches("https://example.com:8443/api/test", matches, includes, excludes))
    }

    @Test
    fun testIncludeRegexLiterals() {
        val matches = emptyList<String>()
        val includes = listOf("""/https?:\/\/([a-z0-9]+\.)?reddit\.com\/.*/""")
        val excludes = emptyList<String>()

        assertTrue(UrlMatcher.matches("https://www.reddit.com/r/android", matches, includes, excludes))
        assertTrue(UrlMatcher.matches("https://old.reddit.com/r/technology", matches, includes, excludes))
        assertFalse(UrlMatcher.matches("https://twitter.com/home", matches, includes, excludes))
    }

    @Test
    fun testAllUrls() {
        val matches = listOf("<all_urls>")
        val includes = emptyList<String>()
        val excludes = emptyList<String>()

        assertTrue(UrlMatcher.matches("https://anything.com/page", matches, includes, excludes))
        assertTrue(UrlMatcher.matches("http://local.dev/", matches, includes, excludes))
    }
}
