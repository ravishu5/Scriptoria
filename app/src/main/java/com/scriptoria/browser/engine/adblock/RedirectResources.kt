package com.scriptoria.browser.engine.adblock

import android.util.Base64
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Stub bodies served in place of a blocked request by the $redirect / $empty / $mp4 filter options.
 *
 * Some pages stall or take an error path when a resource simply fails, so a filter can ask for a
 * neutral stand-in — a transparent pixel, an empty script, a zero-length video — instead of a dead
 * request. Only the content-free placeholders are covered here: uBlock Origin's behavioural
 * surrogates (its stand-ins for analytics.js and friends) are GPL-3.0 program code and are not
 * bundled, so filters naming one fall back to a plain block.
 *
 * The binaries below were generated for this file, not copied from a filter-list project.
 */
internal object RedirectResources {

    private class Stub(val mime: String, val body: ByteArray)

    private val EMPTY = ByteArray(0)

    private val GIF_1X1 = decode(
        "R0lGODlhAQABAIAAAAAAAAAAACH5BAEAAAAALAAAAAABAAEAAAgEAAEEBAA7"
    )
    private val PNG_2X2 = decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAC0lEQVR42mNgQAcAABIAAeRVjecAAAAASUVORK5C" +
        "YII="
    )
    private val PNG_3X2 = decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAC0lEQVR42mNgwAUAABoAAS+Yl6YAAAAASUVORK5C" +
        "YII="
    )
    private val PNG_32X32 = decode(
        "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGklEQVR42u3BAQEAAACCIP+vbkhAAQAAAO8GECAA" +
        "Acm1w7EAAAAASUVORK5CYII="
    )

    /** A structurally valid, zero-sample MP4 so a player sees a finished video, not a failure. */
    private val NOOP_MP4 = decode(
        "AAAAHGZ0eXBpc29tAAACAGlzb21pc28ybXA0MQAAAcltb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAD6AAB" +
        "AAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAACAAABVXRyYWsAAABcdGtoZAAAAAcAAAAAAAAAAAAAAAEAAAAAAAAD6AAAAAAAAAAAAAAAAAAA" +
        "AAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAEAAAABAAAAAAAPFtZGlhAAAAIG1kaGQAAAAA" +
        "AAAAAAAAAAAAAAPoAAAD6FXEAAAAAAAtaGRscgAAAAAAAAAAdmlkZQAAAAAAAAAAAAAAAFZpZGVvSGFuZGxlcgAA" +
        "AACcbWluZgAAABR2bWhkAAAAAQAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAAA" +
        "XHN0YmwAAAAQc3RzZAAAAAAAAAAAAAAAEHN0dHMAAAAAAAAAAAAAABBzdHNjAAAAAAAAAAAAAAAUc3RzegAAAAAA" +
        "AAAAAAAAAAAAABBzdGNvAAAAAAAAAAAAAAAIbWRhdA=="
    )

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

    private val STUBS: Map<String, Stub> = buildMap {
        fun put(mime: String, body: ByteArray, vararg names: String) {
            names.forEach { put(it, Stub(mime, body)) }
        }
        put("text/plain", EMPTY, "empty", "nooptext", "noop.txt")
        put("application/javascript", EMPTY, "noopjs", "noop.js", "noop.js:99999")
        put("text/css", EMPTY, "noopcss", "noop.css")
        put("text/html", "<!DOCTYPE html>".toByteArray(), "noopframe", "noop.html", "noop-0.1s.html")
        put("image/gif", GIF_1X1, "1x1.gif", "1x1-transparent.gif", "noopgif")
        put("image/png", PNG_2X2, "2x2.png", "2x2-transparent.png")
        put("image/png", PNG_3X2, "3x2.png", "3x2-transparent.png")
        put("image/png", PNG_32X32, "32x32.png", "32x32-transparent.png")
        put("video/mp4", NOOP_MP4, "noopmp4-1s", "noop-1s.mp4", "noopmp4")
        put(
            "text/xml",
            ("<VAST version=\"3.0\"></VAST>").toByteArray(),
            "noopvast-2.0", "noopvast-3.0", "noopvast-4.0",
        )
        put(
            "text/xml",
            ("<VMAP xmlns=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\"></VMAP>")
                .toByteArray(),
            "noopvmap-1.0", "noopvmap-1.0.xml",
        )
        put("application/json", "{}".toByteArray(), "noopjson", "noop.json")
    }

    /** @return null when the named resource is not one of the neutral stubs. */
    fun responseFor(name: String?): WebResourceResponse? {
        // Filter lists write the option as either "noopjs" or "noopjs:5" (a cache hint).
        val stub = STUBS[name?.substringBefore(':')] ?: STUBS[name] ?: return null
        return WebResourceResponse(stub.mime, null, ByteArrayInputStream(stub.body))
    }
}
