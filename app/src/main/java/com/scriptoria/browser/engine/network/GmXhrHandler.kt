package com.scriptoria.browser.engine.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Handles cross-origin network requests initiated by `GM_xmlhttpRequest`.
 * Executes asynchronously on IO dispatcher and dispatches events back to the JS hub.
 */
class GmXhrHandler(
    private val httpClient: OkHttpClient,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun execute(
        reqId: String,
        detailsJson: String,
        onEvent: (event: String, payloadJson: String) -> Unit
    ) {
        coroutineScope.launch {
            try {
                val details = JSONObject(detailsJson)
                val method = details.optString("method", "GET").uppercase()
                val url = details.getString("url")
                val timeoutMs = details.optLong("timeout", 0L)
                val data = if (details.has("data")) details.getString("data") else null

                val client = if (timeoutMs > 0) {
                    httpClient.newBuilder()
                        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .build()
                } else {
                    httpClient
                }

                val requestBuilder = Request.Builder().url(url)

                var contentType = "application/x-www-form-urlencoded"
                if (details.has("headers")) {
                    val headersObj = details.getJSONObject("headers")
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val headerName = keys.next()
                        val headerValue = headersObj.getString(headerName)
                        if (headerName.equals("Content-Type", ignoreCase = true)) {
                            contentType = headerValue
                        }
                        requestBuilder.addHeader(headerName, headerValue)
                    }
                }

                val requestBody = when {
                    data != null && (method == "POST" || method == "PUT" || method == "PATCH") -> {
                        data.toRequestBody(contentType.toMediaTypeOrNull())
                    }
                    (method == "POST" || method == "PUT" || method == "PATCH") -> {
                        ByteArray(0).toRequestBody(null)
                    }
                    else -> null
                }

                requestBuilder.method(method, requestBody)
                val call = client.newCall(requestBuilder.build())
                activeCalls[reqId] = call

                onEvent("readystatechange", JSONObject().apply {
                    put("readyState", 1)
                }.toString())

                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    activeCalls.remove(reqId)
                    if (call.isCanceled()) {
                        onEvent("abort", JSONObject().put("error", "Request aborted").toString())
                    } else {
                        onEvent("error", JSONObject().put("error", e.message ?: "Network error").toString())
                    }
                    return@launch
                }

                activeCalls.remove(reqId)

                val responseHeadersString = buildString {
                    for (i in 0 until response.headers.size) {
                        append(response.headers.name(i))
                        append(": ")
                        append(response.headers.value(i))
                        append("\r\n")
                    }
                }

                val responseBodyText = response.body?.string().orEmpty()
                val statusCode = response.code
                val statusText = response.message
                val finalUrl = response.request.url.toString()

                val resultPayload = JSONObject().apply {
                    put("status", statusCode)
                    put("statusText", statusText)
                    put("readyState", 4)
                    put("responseHeaders", responseHeadersString)
                    put("responseText", responseBodyText)
                    put("finalUrl", finalUrl)
                }.toString()

                onEvent("load", resultPayload)

            } catch (e: Exception) {
                activeCalls.remove(reqId)
                onEvent("error", JSONObject().put("error", e.message ?: "Unknown error").toString())
            }
        }
    }

    fun abort(reqId: String) {
        val call = activeCalls.remove(reqId)
        call?.cancel()
    }
}
