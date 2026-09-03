package com.scriptoria.browser.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "userscripts")
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val enabled: Boolean = true,
    val matchesJson: String = "[]",
    val includesJson: String = "[]",
    val excludesJson: String = "[]",
    val grantsJson: String = "[]",
    val requiresJson: String = "[]",
    val connectsJson: String = "[]",
    val runAt: String = "document-end",
    val noFrames: Boolean = false,
    val installUrl: String? = null,
    val updateUrl: String? = null,
    val downloadUrl: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val lastExecuted: Long = 0,
    val executionOrder: Int = 0
)
