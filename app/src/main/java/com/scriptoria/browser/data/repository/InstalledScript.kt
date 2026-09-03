package com.scriptoria.browser.data.repository

import com.scriptoria.browser.engine.parser.UserscriptMetadata

/**
 * High-level model representing an installed userscript with its code,
 * parsed metadata, database ID, and enabled state.
 */
data class InstalledScript(
    val id: Long = 0,
    val metadata: UserscriptMetadata,
    val code: String,
    val enabled: Boolean = true,
    val installUrl: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val lastExecuted: Long = 0,
    val executionOrder: Int = 0
)
