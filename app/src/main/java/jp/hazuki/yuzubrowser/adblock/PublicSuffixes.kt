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

import okhttp3.internal.publicsuffix.PublicSuffixDatabase

/**
 * Registrable-domain lookups, used to tell a first-party request from a third-party one.
 *
 * The list itself comes from OkHttp, which ships the Mozilla public suffix list but exposes it
 * under `okhttp3.internal` — a package with no compatibility promise. Routing every caller through
 * here means an OkHttp upgrade that moves or renames it degrades to the naive last-two-labels rule
 * instead of taking down every page load, and [isAvailable] lets a test say so out loud.
 */
object PublicSuffixes {

    /** False once the OkHttp lookup has thrown, so the fallback is not re-tried through it. */
    @Volatile
    private var okHttpUsable = true

    fun effectiveTldPlusOne(domain: String): String? {
        if (okHttpUsable) {
            try {
                return PublicSuffixDatabase.get().getEffectiveTldPlusOne(domain)
            } catch (e: Throwable) {
                okHttpUsable = false
            }
        }
        return fallback(domain)
    }

    /**
     * Wrong for multi-label suffixes such as .co.uk, which is why it is only a fallback: it makes
     * a same-site request look third-party, so filters over-block rather than under-block.
     */
    private fun fallback(domain: String): String? {
        val labels = domain.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return null
        return labels.takeLast(2).joinToString(".")
    }

    /** Whether the OkHttp-backed list is still reachable; false means results are approximate. */
    fun isAvailable(): Boolean = okHttpUsable && runCatching {
        PublicSuffixDatabase.get().getEffectiveTldPlusOne("example.com") == "example.com"
    }.getOrDefault(false)
}
