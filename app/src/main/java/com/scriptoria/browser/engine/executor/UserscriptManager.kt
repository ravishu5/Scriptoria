package com.scriptoria.browser.engine.executor

import android.content.Context
import android.util.Log
import com.scriptoria.browser.data.repository.GmStorageRepository
import com.scriptoria.browser.data.repository.InstalledScript
import com.scriptoria.browser.data.repository.ScriptRepository
import com.scriptoria.browser.engine.matcher.UrlMatcher
import com.scriptoria.browser.engine.parser.RunAt
import com.scriptoria.browser.engine.parser.UserscriptMetadata
import com.scriptoria.browser.engine.parser.UserscriptParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

sealed class ScriptUpdateStatus {
    data class Updated(val scriptName: String, val oldVersion: String, val newVersion: String) : ScriptUpdateStatus()
    data class UpToDate(val scriptName: String) : ScriptUpdateStatus()
    data class Failed(val scriptName: String, val error: String) : ScriptUpdateStatus()
    object NoSource : ScriptUpdateStatus()
}

class UserscriptManager(
    private val context: Context,
    val scriptRepository: ScriptRepository,
    val gmStorageRepository: GmStorageRepository,
    private val requireManager: RequireManager,
    private val httpClient: OkHttpClient,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val _scripts = MutableStateFlow<List<InstalledScript>>(emptyList())
    val scripts: StateFlow<List<InstalledScript>> = _scripts.asStateFlow()

    private var gmShimTemplate: String = ""

    init {
        loadShimTemplate()
        coroutineScope.launch {
            scriptRepository.seedPreinstalledScripts(context)
            reload()
        }
    }

    private fun loadShimTemplate() {
        try {
            gmShimTemplate = context.assets.open("gm_shim.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e("UserscriptManager", "Failed to load gm_shim.js asset", e)
        }
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        val loaded = scriptRepository.getAll()
        _scripts.value = loaded
    }

    fun getMatchingScripts(url: String, runAt: RunAt): List<InstalledScript> {
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
            return emptyList()
        }

        return _scripts.value.filter { script ->
            script.enabled &&
                    script.metadata.runAt == runAt &&
                    UrlMatcher.matches(
                        url = url,
                        matches = script.metadata.matches,
                        includes = script.metadata.includes,
                        excludes = script.metadata.excludes
                    )
        }.sortedBy { it.executionOrder }
    }

    fun getActiveScriptsForUrl(url: String): List<InstalledScript> {
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
            return emptyList()
        }

        return _scripts.value.filter { script ->
            script.enabled &&
                    UrlMatcher.matches(
                        url = url,
                        matches = script.metadata.matches,
                        includes = script.metadata.includes,
                        excludes = script.metadata.excludes
                    )
        }
    }

    suspend fun buildInjectionBundle(script: InstalledScript, capabilityToken: String): String = withContext(Dispatchers.IO) {
        val gmInfoJson = buildGmInfoJson(script)
        val populatedShim = gmShimTemplate
            .replace("__SCRIPT_ID__", script.id.toString())
            .replace("__GM_TOKEN__", JSONObject.quote(capabilityToken))
            .replace("__GM_INFO__", gmInfoJson)

        val requires = requireManager.resolveRequires(script.metadata.requires)

        buildString {
            append("(function() {\n")
            append(populatedShim).append("\n")
            if (requires.isNotBlank()) {
                append(requires).append("\n")
            }
            append(script.code).append("\n")
            append("})();\n")
            append("//# sourceURL=scriptoria-${script.id}-${script.metadata.name.replace(' ', '_')}.user.js\n")
        }
    }

    private fun buildGmInfoJson(script: InstalledScript): String {
        val m = script.metadata
        val scriptObj = JSONObject().apply {
            put("name", m.name)
            put("namespace", m.namespace)
            put("version", m.version)
            put("description", m.description)
            put("author", m.author)
            put("runAt", m.runAt.tagValue)
            put("matches", JSONArray(m.matches))
            put("includes", JSONArray(m.includes))
            put("excludes", JSONArray(m.excludes))
            put("grant", JSONArray(m.grants))
            put("connects", JSONArray(m.connects))
        }

        return JSONObject().apply {
            put("script", scriptObj)
            put("scriptHandler", "Scriptoria")
            put("version", "1.0.0")
            put("scriptMetaStr", "")
            put("uuid", script.id.toString())
        }.toString()
    }

    suspend fun installScript(code: String, sourceUrl: String? = null): Long {
        val id = scriptRepository.addOrUpdate(code, sourceUrl)
        reload()
        return id
    }

    suspend fun toggleScript(id: Long, enabled: Boolean) {
        scriptRepository.setEnabled(id, enabled)
        reload()
    }

    suspend fun deleteScript(id: Long) {
        scriptRepository.delete(id)
        gmStorageRepository.clearForScript(id)
        reload()
    }

    suspend fun updateScriptCode(id: Long, newCode: String) {
        val current = scriptRepository.getById(id) ?: return
        val updated = current.copy(code = newCode)
        scriptRepository.update(updated)
        reload()
    }

    suspend fun checkForUpdate(id: Long): ScriptUpdateStatus = withContext(Dispatchers.IO) {
        val script = scriptRepository.getById(id) ?: return@withContext ScriptUpdateStatus.Failed("Unknown", "Script not found")
        val meta = script.metadata
        val checkUrl = meta.updateUrl ?: meta.downloadUrl ?: script.installUrl ?: return@withContext ScriptUpdateStatus.NoSource

        try {
            val request = Request.Builder().url(checkUrl).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            response.close()

            if (body.isBlank()) {
                return@withContext ScriptUpdateStatus.Failed(meta.name, "Empty response from update URL")
            }

            val remoteMeta = UserscriptParser.parse(body, checkUrl)
            if (UserscriptParser.isNewerVersion(remoteMeta.version, meta.version)) {
                // If updateUrl pointed to a meta-only file, fetch the full script from downloadUrl
                val fullCode = if (meta.downloadUrl != null && meta.downloadUrl != checkUrl) {
                    val dlReq = Request.Builder().url(meta.downloadUrl).build()
                    val dlResp = httpClient.newCall(dlReq).execute()
                    val dlBody = dlResp.body?.string().orEmpty()
                    dlResp.close()
                    dlBody
                } else {
                    body
                }

                scriptRepository.addOrUpdate(fullCode, checkUrl)
                reload()
                ScriptUpdateStatus.Updated(meta.name, meta.version, remoteMeta.version)
            } else {
                ScriptUpdateStatus.UpToDate(meta.name)
            }
        } catch (e: Exception) {
            ScriptUpdateStatus.Failed(meta.name, e.message ?: "Update check failed")
        }
    }

    // ==========================================
    // Backup Import & Export
    // ==========================================
    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val allScripts = scriptRepository.getAll()
        val backupArr = JSONArray()

        for (s in allScripts) {
            val storageMap = gmStorageRepository.getAllForScript(s.id)
            val scriptObj = JSONObject().apply {
                put("name", s.metadata.name)
                put("code", s.code)
                put("enabled", s.enabled)
                put("installUrl", s.installUrl)
                put("storage", JSONObject(storageMap))
            }
            backupArr.put(scriptObj)
        }

        JSONObject().apply {
            put("version", 1)
            put("timestamp", System.currentTimeMillis())
            put("scripts", backupArr)
        }.toString(2)
    }

    suspend fun importBackupJson(jsonString: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(jsonString)
        val arr = root.getJSONArray("scripts")
        var count = 0

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val code = obj.getString("code")
            val enabled = obj.optBoolean("enabled", true)
            val installUrl = if (obj.has("installUrl")) obj.getString("installUrl") else null

            val id = scriptRepository.addOrUpdate(code, installUrl)
            scriptRepository.setEnabled(id, enabled)

            if (obj.has("storage")) {
                val storageObj = obj.getJSONObject("storage")
                val keys = storageObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = storageObj.getString(k)
                    gmStorageRepository.setValue(id, k, v)
                }
            }
            count++
        }
        reload()
        count
    }
}
