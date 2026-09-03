package com.scriptoria.browser.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit

class DownloadPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var customFolderUriString: String?
        get() = prefs.getString(KEY_CUSTOM_URI, null)
        set(value) = prefs.edit { putString(KEY_CUSTOM_URI, value) }

    var folderDisplayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME
        set(value) = prefs.edit { putString(KEY_DISPLAY_NAME, value) }

    fun setCustomDirectory(treeUri: Uri, displayName: String) {
        customFolderUriString = treeUri.toString()
        folderDisplayName = displayName
    }

    fun resetToDefault() {
        prefs.edit {
            remove(KEY_CUSTOM_URI)
            putString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME)
        }
    }

    val isCustomLocation: Boolean
        get() = !customFolderUriString.isNullOrBlank()

    companion object {
        private const val PREFS_NAME = "scriptoria_download_prefs"
        private const val KEY_CUSTOM_URI = "custom_folder_uri"
        private const val KEY_DISPLAY_NAME = "folder_display_name"
        const val DEFAULT_DISPLAY_NAME = "Downloads/Scriptoria"
    }
}
