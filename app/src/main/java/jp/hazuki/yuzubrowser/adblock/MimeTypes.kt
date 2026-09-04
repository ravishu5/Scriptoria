/*
 * Copyright (C) 2026 Scriptoria
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.hazuki.yuzubrowser.adblock

import android.webkit.MimeTypeMap

const val MIME_TYPE_UNKNOWN = "application/octet-stream"

/**
 * Used only to guess a request's content type from its file extension, so the handful of
 * extensions MimeTypeMap gets wrong or omits are worth special-casing: a .js served as
 * "text/plain" would be filtered as a document rather than a script.
 */
fun getMimeTypeFromExtension(extension: String): String = when (extension) {
    "js" -> "application/javascript"
    "mhtml", "mht" -> "multipart/related"
    "json" -> "application/json"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?.takeIf { it.isNotEmpty() }
        ?: MIME_TYPE_UNKNOWN
}
