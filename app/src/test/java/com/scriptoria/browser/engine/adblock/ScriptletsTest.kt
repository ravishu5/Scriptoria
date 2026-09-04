package com.scriptoria.browser.engine.adblock

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScriptletsTest {

    @Test
    fun splitsNameAndArguments() {
        assertEquals(
            listOf("set-constant", "adBlockDetected", "false"),
            Scriptlets.parse("set-constant, adBlockDetected, false"),
        )
    }

    @Test
    fun aScriptletWithNoArgumentsIsJustItsName() {
        assertEquals(listOf("nowebrtc"), Scriptlets.parse("nowebrtc"))
    }

    @Test
    fun escapedCommaStaysInsideItsArgument() {
        // Regular-expression arguments routinely contain commas.
        assertEquals(
            listOf("prevent-setTimeout", "/ad{1,3}/"),
            Scriptlets.parse("prevent-setTimeout, /ad{1\\,3}/"),
        )
    }

    @Test
    fun wrappingQuotesAreStripped() {
        assertEquals(
            listOf("set-constant", "foo", "bar, baz"),
            Scriptlets.parse("set-constant, 'foo', 'bar\\, baz'"),
        )
    }

    @Test
    fun apostropheInsideAnArgumentIsKept() {
        assertEquals(listOf("log", "it's here"), Scriptlets.parse("log, it's here"))
    }

    @Test
    fun trailingEmptyArgumentsAreDropped() {
        // They would otherwise shift a scriptlet's optional parameters.
        assertEquals(listOf("remove-attr", "onclick"), Scriptlets.parse("remove-attr, onclick, "))
    }

    @Test
    fun buildsTheJsonTheLibraryExpects() {
        val json = JSONArray(Scriptlets.toJson(listOf("set-constant, foo, false", "nowebrtc")))
        assertEquals(2, json.length())
        assertEquals("set-constant", json.getJSONArray(0).getString(0))
        assertEquals("false", json.getJSONArray(0).getString(2))
        assertEquals(1, json.getJSONArray(1).length())
    }

    @Test
    fun emptyCallsAreSkipped() {
        assertEquals("[]", Scriptlets.toJson(listOf("", "   ")))
    }
}
