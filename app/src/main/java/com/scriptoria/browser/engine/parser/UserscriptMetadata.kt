package com.scriptoria.browser.engine.parser

enum class RunAt(val tagValue: String) {
    DOCUMENT_START("document-start"),
    DOCUMENT_BODY("document-body"),
    DOCUMENT_END("document-end"),
    DOCUMENT_IDLE("document-idle");

    companion object {
        fun fromString(value: String?): RunAt {
            return when (value?.lowercase()?.trim()) {
                "document-start" -> DOCUMENT_START
                "document-body" -> DOCUMENT_BODY
                "document-idle" -> DOCUMENT_IDLE
                else -> DOCUMENT_END // Default per Tampermonkey specification
            }
        }
    }
}

/**
 * Parsed metadata block from `// ==UserScript== ... // ==/UserScript==`
 */
data class UserscriptMetadata(
    val name: String,
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val resources: Map<String, String> = emptyMap(),
    val connects: List<String> = emptyList(),
    val runAt: RunAt = RunAt.DOCUMENT_END,
    val noFrames: Boolean = false,
    val updateUrl: String? = null,
    val downloadUrl: String? = null,
    val installUrl: String? = null
)
