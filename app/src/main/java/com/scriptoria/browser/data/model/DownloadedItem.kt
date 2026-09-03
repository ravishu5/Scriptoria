package com.scriptoria.browser.data.model

import android.net.Uri

enum class DownloadType {
    VIDEO,
    IMAGE,
    AUDIO,
    ARCHIVE,
    DOCUMENT,
    OTHER
}

data class DownloadedItem(
    val name: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val lastModified: Long,
    val formattedDate: String,
    val mimeType: String,
    val type: DownloadType,
    val uri: Uri,
    val filePath: String? = null
)
