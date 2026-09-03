package com.scriptoria.browser.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.scriptoria.browser.data.database.entities.GmStorageEntity
import com.scriptoria.browser.data.database.entities.ScriptEntity

@Database(
    entities = [
        ScriptEntity::class,
        GmStorageEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun gmStorageDao(): GmStorageDao
}
