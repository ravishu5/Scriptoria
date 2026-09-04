package com.scriptoria.browser.engine.network

import android.webkit.CookieManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Shared HTTP details for media downloads.
 *
 * These fetches are made by us rather than by WebView, so nothing carries the browsing session
 * over automatically — a CDN that was happy to serve the page will answer 403 without it.
 */
internal object MediaHttp {

    fun Request.Builder.applyMediaHeaders(
        url: String,
        userAgent: String?,
        referer: String?
    ): Request.Builder = apply {
        userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
        referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { header("Cookie", it) }
    }

    /**
     * Streams [url] into [dest].
     *
     * @param onBytes called with the running byte count for this file.
     * @return the number of bytes written.
     */
    fun downloadToFile(
        url: String,
        dest: File,
        userAgent: String?,
        referer: String?,
        client: OkHttpClient,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit = {}
    ): Long {
        val request = Request.Builder().url(url)
            .applyMediaHeaders(url, userAgent, referer)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty response for $url")

            var written = 0L
            body.byteStream().use { input ->
                dest.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) return written
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                    output.flush()
                }
            }
            return written
        }
    }
}
