package com.scriptoria.browser.engine.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserscriptParserTest {

    @Test
    fun testStandardMetadataParsing() {
        val script = """
            // ==UserScript==
            // @name         YouTube Enhancer Pro
            // @namespace    https://enhancer.org
            // @version      2.4.1
            // @description  High quality playback and ad skipping
            // @author       DevTeam
            // @match        https://*.youtube.com/*
            // @match        https://youtube.com/*
            // @include      http://*.youtube.com/*
            // @exclude      https://youtube.com/embed/*
            // @grant        GM_getValue
            // @grant        GM_setValue
            // @grant        GM_xmlhttpRequest
            // @require      https://cdn.jsdelivr.net/npm/jquery@3.6.0/dist/jquery.min.js
            // @resource     customCss https://example.com/style.css
            // @run-at       document-start
            // @noframes
            // @updateURL    https://example.com/script.meta.js
            // @downloadURL  https://example.com/script.user.js
            // ==/UserScript==
            console.log("Hello from userscript");
        """.trimIndent()

        val meta = UserscriptParser.parse(script)

        assertEquals("YouTube Enhancer Pro", meta.name)
        assertEquals("https://enhancer.org", meta.namespace)
        assertEquals("2.4.1", meta.version)
        assertEquals("High quality playback and ad skipping", meta.description)
        assertEquals("DevTeam", meta.author)

        assertEquals(2, meta.matches.size)
        assertTrue(meta.matches.contains("https://*.youtube.com/*"))
        assertTrue(meta.matches.contains("https://youtube.com/*"))

        assertEquals(1, meta.includes.size)
        assertTrue(meta.includes.contains("http://*.youtube.com/*"))

        assertEquals(1, meta.excludes.size)
        assertTrue(meta.excludes.contains("https://youtube.com/embed/*"))

        assertEquals(3, meta.grants.size)
        assertTrue(meta.grants.contains("GM_getValue"))
        assertTrue(meta.grants.contains("GM_setValue"))
        assertTrue(meta.grants.contains("GM_xmlhttpRequest"))

        assertEquals(1, meta.requires.size)
        assertEquals("https://cdn.jsdelivr.net/npm/jquery@3.6.0/dist/jquery.min.js", meta.requires[0])

        assertEquals(1, meta.resources.size)
        assertEquals("https://example.com/style.css", meta.resources["customCss"])

        assertEquals(RunAt.DOCUMENT_START, meta.runAt)
        assertTrue(meta.noFrames)
        assertEquals("https://example.com/script.meta.js", meta.updateUrl)
        assertEquals("https://example.com/script.user.js", meta.downloadUrl)
    }

    @Test
    fun testFallbackAndDefaults() {
        val script = """
            // ==UserScript==
            // @version 1.0
            // ==/UserScript==
            alert(1);
        """.trimIndent()

        val meta = UserscriptParser.parse(script, "https://example.org/myscript.user.js")

        assertEquals("myscript", meta.name)
        assertEquals("1.0", meta.version)
        assertEquals(RunAt.DOCUMENT_END, meta.runAt) // Default per spec
        assertFalse(meta.noFrames)
    }

    @Test
    fun testVersionComparison() {
        assertTrue(UserscriptParser.isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(UserscriptParser.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(UserscriptParser.isNewerVersion("1.2.10", "1.2.2"))
        assertTrue(UserscriptParser.isNewerVersion("1.0.0", "1.0.0-beta"))
        assertTrue(UserscriptParser.isNewerVersion("2026.09.01", "2026.08.30"))

        assertFalse(UserscriptParser.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(UserscriptParser.isNewerVersion("1.0.0", "1.0.1"))
        assertFalse(UserscriptParser.isNewerVersion("1.0.0-alpha", "1.0.0"))
    }

    @Test
    fun testIndentedMetadataParsing() {
        val script = "    // ==UserScript==\n    // @name Claude Telegram\n    // @match *://web.telegram.org/*\n    // @run-at document-idle\n    // ==/UserScript=="
        val meta = UserscriptParser.parse(script)
        assertEquals("Claude Telegram", meta.name)
        assertEquals(1, meta.matches.size)
        assertEquals("*://web.telegram.org/*", meta.matches[0])
        assertEquals(RunAt.DOCUMENT_IDLE, meta.runAt)
    }
}
