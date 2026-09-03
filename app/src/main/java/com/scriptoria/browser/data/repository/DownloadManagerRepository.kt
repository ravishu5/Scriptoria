package com.scriptoria.browser.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.scriptoria.browser.data.model.DownloadType
import com.scriptoria.browser.data.model.DownloadedItem
import com.scriptoria.browser.data.preferences.DownloadPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadManagerRepository(
    private val context: Context,
    private val downloadPreferences: DownloadPreferences
) {

    fun getDownloadedFiles(): List<DownloadedItem> {
        val customUriStr = downloadPreferences.customFolderUriString
        if (!customUriStr.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(customUriStr)
                val docDir = DocumentFile.fromTreeUri(context, treeUri)
                if (docDir != null && docDir.isDirectory) {
                    return docDir.listFiles()
                        .filter { it.isFile && isVisibleDownload(it.name) }
                        .mapNotNull { file ->
                            val name = file.name ?: return@mapNotNull null
                            val size = file.length()
                            val lastMod = file.lastModified()
                            val mime = file.type?.ifEmpty { null } ?: guessMimeType(name)
                            DownloadedItem(
                                name = name,
                                sizeBytes = size,
                                formattedSize = formatSize(size),
                                lastModified = lastMod,
                                formattedDate = formatDate(lastMod),
                                mimeType = mime,
                                type = resolveDownloadType(name, mime),
                                uri = file.uri
                            )
                        }
                        .sortedByDescending { it.lastModified }
                }
            } catch (e: Exception) {
                Log.e("DownloadRepo", "Error reading custom SAF directory", e)
            }
        }

        // Default: Read from public Downloads/Scriptoria folder
        val items = mutableListOf<DownloadedItem>()
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Scriptoria")
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.filter { it.isFile && isVisibleDownload(it.name) }?.forEach { file ->
                val name = file.name
                val size = file.length()
                val lastMod = file.lastModified()
                val mime = guessMimeType(name)
                val fileUri = try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    Uri.fromFile(file)
                }

                items.add(
                    DownloadedItem(
                        name = name,
                        sizeBytes = size,
                        formattedSize = formatSize(size),
                        lastModified = lastMod,
                        formattedDate = formatDate(lastMod),
                        mimeType = mime,
                        type = resolveDownloadType(name, mime),
                        uri = fileUri,
                        filePath = file.absolutePath
                    )
                )
            }
        }

        return items.sortedByDescending { it.lastModified }
    }

    fun openFile(context: Context, item: DownloadedItem) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open ${item.name}"))
        } catch (e: Exception) {
            Log.e("DownloadRepo", "Cannot open file: ${item.name}", e)
        }
    }

    fun shareFile(context: Context, item: DownloadedItem) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, item.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${item.name}"))
        } catch (e: Exception) {
            Log.e("DownloadRepo", "Cannot share file: ${item.name}", e)
        }
    }

    fun deleteFile(item: DownloadedItem): Boolean {
        return try {
            if (item.filePath != null) {
                val f = File(item.filePath)
                if (f.exists()) f.delete() else false
            } else {
                val docFile = DocumentFile.fromSingleUri(context, item.uri)
                docFile?.delete() ?: false
            }
        } catch (e: Exception) {
            Log.e("DownloadRepo", "Cannot delete file: ${item.name}", e)
            false
        }
    }

    companion object {
        /**
         * A download still being written through MediaStore exists on disk as
         * `.pending-<id>-<name>` until IS_PENDING is cleared. Those placeholders are not files
         * the user saved, so they must not appear in the list — the in-progress section already
         * shows that transfer. Dotfiles are excluded generally, which covers the same ground.
         */
        fun isVisibleDownload(name: String?): Boolean =
            !name.isNullOrBlank() && !name.startsWith(".")

        fun resolveDownloadType(name: String, mime: String): DownloadType {
            val lower = name.lowercase()
            return when {
                mime.startsWith("video/") || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".avi") -> DownloadType.VIDEO
                mime.startsWith("image/") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp") -> DownloadType.IMAGE
                mime.startsWith("audio/") || lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".m4a") -> DownloadType.AUDIO
                lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz") -> DownloadType.ARCHIVE
                lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") -> DownloadType.DOCUMENT
                else -> DownloadType.OTHER
            }
        }

        fun guessMimeType(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp3" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                "wav" -> "audio/wav"
                "zip" -> "application/zip"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
        }

        fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024 * 1024))
                bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024))
                bytes >= 1024 -> "${bytes / 1024} KB"
                else -> "$bytes B"
            }
        }

        fun formatDate(timestamp: Long): String {
            if (timestamp <= 0) return ""
            val sdf = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }
}
