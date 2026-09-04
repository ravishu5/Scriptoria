package com.scriptoria.browser.data.config

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppConfig(
    val minVersionCode: Long,
    val latestVersionCode: Long,
    val latestVersionName: String,
    val updateUrl: String,
    val message: String?
)

enum class UpdateStatus {
    /** Current build is fine. */
    NONE,

    /** A newer build exists; the user may keep using this one. */
    OPTIONAL,

    /** Below minVersionCode — the app should not be usable until updated. */
    REQUIRED
}

/**
 * Minimum-supported-version gate.
 *
 * Deliberately fetched rather than bundled: a config shipped inside the APK is frozen at build
 * time, so an old install would carry an old minimum and could never be told to update. Hosting
 * it means raising the bar reaches builds that are already out in the field.
 *
 * Fails open on purpose. A network blip, a typo in the JSON, or GitHub being down must never
 * brick the browser, so anything unexpected leaves the app usable; only a well-formed config
 * that explicitly outranks this build can block it. The last good config is cached so an
 * offline launch still behaves.
 */
class AppConfigRepository(
    private val context: Context,
    private val httpClient: OkHttpClient
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val currentVersionCode: Long by lazy {
        try {
            val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read own version", e)
            Long.MAX_VALUE   // unknown version must never be treated as outdated
        }
    }

    /** Fetches the config, caching it on success. Returns the cached copy if the fetch fails. */
    suspend fun refresh(): AppConfig? = withContext(Dispatchers.IO) {
        try {
            val client = httpClient.newBuilder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(CONFIG_URL).build()).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val config = parse(body)
                prefs.edit { putString(KEY_CACHED, body) }
                Log.i(TAG, "Config: min=${config.minVersionCode} latest=${config.latestVersionCode} here=$currentVersionCode")
                config
            }
        } catch (e: Exception) {
            Log.w(TAG, "Config fetch failed (${e.message}); using cached copy if present")
            cached()
        }
    }

    fun cached(): AppConfig? = prefs.getString(KEY_CACHED, null)?.let {
        try {
            parse(it)
        } catch (e: Exception) {
            null
        }
    }

    fun statusFor(config: AppConfig?): UpdateStatus {
        if (config == null) return UpdateStatus.NONE
        return when {
            currentVersionCode < config.minVersionCode -> UpdateStatus.REQUIRED
            currentVersionCode < config.latestVersionCode -> UpdateStatus.OPTIONAL
            else -> UpdateStatus.NONE
        }
    }

    private fun parse(json: String): AppConfig {
        val root = JSONObject(json)
        return AppConfig(
            minVersionCode = root.optLong("minVersionCode", 0L),
            latestVersionCode = root.optLong("latestVersionCode", 0L),
            latestVersionName = root.optString("latestVersionName", ""),
            updateUrl = root.optString("updateUrl", DEFAULT_UPDATE_URL),
            message = root.optString("message", "").takeIf { it.isNotBlank() }
        )
    }

    private companion object {
        const val TAG = "AppConfig"
        const val PREFS = "scriptoria_app_config"
        const val KEY_CACHED = "cached_config_json"
        const val TIMEOUT_SECONDS = 10L
        const val DEFAULT_UPDATE_URL = "https://github.com/ravishu5/Scriptoria/releases/latest"
        const val CONFIG_URL =
            "https://raw.githubusercontent.com/ravishu5/Scriptoria/main/app_config.json"
    }
}
