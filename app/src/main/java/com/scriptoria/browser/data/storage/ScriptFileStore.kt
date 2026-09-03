package com.scriptoria.browser.data.storage

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * File-backed persistence for userscript code and external `@require` dependencies.
 *
 * Storing multi-megabyte scripts directly in SQLite/Room table columns causes
 * [android.database.sqlite.SQLiteBlobTooBigException] due to Android's 2MB CursorWindow limit.
 * Using disk-based private app storage completely eliminates this limitation.
 */
class ScriptFileStore(context: Context) {

    private val scriptsDir = File(context.filesDir, "userscripts").apply { mkdirs() }
    private val requiresDir = File(context.filesDir, "requires").apply { mkdirs() }

    fun getScriptFile(id: Long): File = File(scriptsDir, "$id.user.js")

    fun readScriptCode(id: Long): String? {
        val file = getScriptFile(id)
        return if (file.exists()) file.readText() else null
    }

    fun writeScriptCode(id: Long, code: String) {
        getScriptFile(id).writeText(code)
    }

    fun deleteScriptCode(id: Long): Boolean {
        return getScriptFile(id).delete()
    }

    private fun hashUrl(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(url.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun getRequireFile(url: String): File = File(requiresDir, "${hashUrl(url)}.js")

    fun readCachedRequire(url: String): String? {
        val file = getRequireFile(url)
        return if (file.exists()) file.readText() else null
    }

    fun writeCachedRequire(url: String, content: String) {
        getRequireFile(url).writeText(content)
    }

    fun clearAllRequires() {
        requiresDir.listFiles()?.forEach { it.delete() }
    }
}
