package com.scriptoria.browser.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.scriptoria.browser.data.preferences.DownloadPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A resolved download destination, honouring the user's chosen folder.
 *
 * Resolution order matches what the user configured in Settings: a custom SAF tree if one is
 * set, otherwise the public Downloads/Scriptoria folder (via MediaStore on Q+).
 *
 * Callers must eventually call either [markComplete] or [discard]. On Q+ the MediaStore entry
 * is created pending, so a download that dies midway never shows up in the gallery or file
 * manager as if it were a finished file.
 */
class DownloadSink private constructor(
    private val context: Context,
    val displayName: String,
    val mimeType: String,
    private val target: Target
) {

    private sealed class Target {
        data class Saf(val uri: Uri) : Target()
        data class Media(val uri: Uri) : Target()
        data class Legacy(val file: File) : Target()
    }

    val uri: Uri
        get() = when (target) {
            is Target.Saf -> target.uri
            is Target.Media -> target.uri
            is Target.Legacy -> Uri.fromFile(target.file)
        }

    fun openOutputStream(): OutputStream = when (target) {
        is Target.Saf -> context.contentResolver.openOutputStream(target.uri)
        is Target.Media -> context.contentResolver.openOutputStream(target.uri)
        is Target.Legacy -> FileOutputStream(target.file)
    } ?: throw IOException("Could not open $displayName for writing")

    /** Publishes the file. Until this runs, a Q+ MediaStore entry stays invisible to other apps. */
    fun markComplete() {
        if (target is Target.Media && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                context.contentResolver.update(target.uri, values, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Could not publish $displayName", e)
            }
        }
    }

    /** Deletes the partial file so a failed download can't be mistaken for a complete one. */
    fun discard() {
        try {
            when (target) {
                is Target.Saf -> DocumentFile.fromSingleUri(context, target.uri)?.delete()
                is Target.Media -> context.contentResolver.delete(target.uri, null, null)
                is Target.Legacy -> target.file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not discard partial file $displayName", e)
        }
    }

    companion object {
        private const val TAG = "DownloadSink"
        private const val SUBFOLDER = "Scriptoria"

        fun create(
            context: Context,
            preferences: DownloadPreferences,
            requestedName: String,
            mimeType: String
        ): DownloadSink {
            val safeName = sanitizeFileName(requestedName)

            preferences.customFolderUriString
                ?.takeIf { it.isNotBlank() }
                ?.let { uriString ->
                    try {
                        val docDir = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
                        // createFile resolves name collisions itself.
                        val created = docDir?.createFile(mimeType, safeName)
                        if (created != null) {
                            return DownloadSink(context, safeName, mimeType, Target.Saf(created.uri))
                        }
                        Log.w(TAG, "Custom folder unusable, falling back to Downloads/$SUBFOLDER")
                    } catch (e: Exception) {
                        Log.e(TAG, "Custom SAF folder failed, falling back", e)
                    }
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + SUBFOLDER
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Could not create $safeName in Downloads/$SUBFOLDER")
                return DownloadSink(context, safeName, mimeType, Target.Media(uri))
            }

            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SUBFOLDER
            )
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("Could not create Downloads/$SUBFOLDER")
            }
            val dest = uniqueFile(dir, safeName)
            return DownloadSink(context, dest.name, mimeType, Target.Legacy(dest))
        }

        fun sanitizeFileName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifEmpty { "download" }

        /** MediaStore and SAF de-duplicate names for us; the legacy file path does not. */
        private fun uniqueFile(dir: File, name: String): File {
            var candidate = File(dir, name)
            if (!candidate.exists()) return candidate
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            var counter = 1
            while (candidate.exists() && counter < 1000) {
                val next = if (ext.isEmpty()) "$base ($counter)" else "$base ($counter).$ext"
                candidate = File(dir, next)
                counter++
            }
            return candidate
        }
    }
}
