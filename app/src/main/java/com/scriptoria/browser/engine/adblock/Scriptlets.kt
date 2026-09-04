package com.scriptoria.browser.engine.adblock

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * The scriptlet library and the parsing of the calls filter lists make into it.
 *
 * A "+js(...)" rule names a small behavioural patch — force a property to false, defuse a timer,
 * prune a JSON response — that runs in the page before its own scripts do. The implementations
 * live in assets/adblock/scriptlets.js; this supplies them to the WebView and turns a rule's call
 * text into the arguments they are invoked with.
 */
internal object Scriptlets {

    private const val ASSET = "adblock/scriptlets.js"

    @Volatile
    private var library: String? = null

    /**
     * The library source, to be registered as a document-start script.
     *
     * Shipping the whole library and passing only arguments at runtime is what keeps this working
     * on pages with a strict Content-Security-Policy: there is no eval and no injected script tag
     * for the policy to refuse.
     */
    fun library(context: Context): String {
        library?.let { return it }
        val loaded = try {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e("Scriptlets", "Could not read $ASSET", e)
            ""
        }
        library = loaded
        return loaded
    }

    /**
     * Turns call texts such as "set-constant, adBlockDetected, false" into a JSON array of
     * [name, arg, ...] arrays for the library's entry point.
     */
    fun toJson(calls: List<String>): String {
        val array = JSONArray()
        for (call in calls) {
            val parsed = parse(call)
            if (parsed.isEmpty() || parsed[0].isEmpty()) continue
            array.put(JSONArray().apply { parsed.forEach { put(it) } })
        }
        return array.toString()
    }

    /**
     * Splits on commas, honouring the "\," escape a filter uses when an argument contains one —
     * a regular-expression argument frequently does.
     */
    fun parse(call: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < call.length) {
            val c = call[i]
            when {
                c == '\\' && i + 1 < call.length && call[i + 1] == ',' -> {
                    current.append(',')
                    i += 2
                }
                c == ',' -> {
                    args += current.toString().unquote()
                    current.setLength(0)
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        args += current.toString().unquote()
        // Trailing empty arguments are meaningless and would shift a scriptlet's parameters.
        while (args.size > 1 && args.last().isEmpty()) args.removeAt(args.size - 1)
        return args
    }

    private fun String.unquote(): String {
        val trimmed = trim()
        if (trimmed.length < 2) return trimmed
        val first = trimmed.first()
        // Only strip quotes that wrap the whole argument; an apostrophe inside one is content.
        if ((first == '\'' || first == '"') && trimmed.last() == first) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return trimmed
    }
}
