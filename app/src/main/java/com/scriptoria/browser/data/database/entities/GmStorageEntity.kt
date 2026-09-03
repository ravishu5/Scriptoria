package com.scriptoria.browser.data.database.entities

import androidx.room.Entity

@Entity(
    tableName = "gm_storage",
    primaryKeys = ["scriptId", "key"]
)
data class GmStorageEntity(
    val scriptId: Long,
    val key: String,
    val valueJson: String
)
