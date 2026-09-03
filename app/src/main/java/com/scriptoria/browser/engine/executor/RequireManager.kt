package com.scriptoria.browser.engine.executor

import android.util.Log
import com.scriptoria.browser.data.storage.ScriptFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads, caches, and bundles `@require` external script dependencies.
 * Ensures dependencies are cached to disk so pages do not repeatedly fetch external scripts.
 */
class RequireManager(
    private val fileStore: ScriptFileStore,
    private val httpClient: OkHttpClient
) {

    private val memoryCache = ConcurrentHashMap<String, String>()

    suspend fun resolveRequires(requireUrls: List<String>): String = withContext(Dispatchers.IO) {
        if (requireUrls.isEmpty()) return@withContext ""

        val sb = StringBuilder()
        for (url in requireUrls) {
            val content = getOrFetchRequire(url)
            if (content.isNotBlank()) {
                sb.append(content).append("\n")
            }
        }
        sb.toString()
    }

    private suspend fun getOrFetchRequire(url: String): String = withContext(Dispatchers.IO) {
        memoryCache[url]?.let { return@withContext it }

        val diskCached = fileStore.readCachedRequire(url)
        if (diskCached != null) {
            memoryCache[url] = diskCached
            return@withContext diskCached
        }

        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            response.close()

            if (body.isNotEmpty()) {
                fileStore.writeCachedRequire(url, body)
                memoryCache[url] = body
            }
            body
        } catch (e: Exception) {
            Log.e("RequireManager", "Failed to fetch @require: $url", e)
            ""
        }
    }
}
