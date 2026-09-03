package com.scriptoria.browser.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.scriptoria.browser.data.database.entities.GmStorageEntity

@Dao
interface GmStorageDao {

    @Query("SELECT valueJson FROM gm_storage WHERE scriptId = :scriptId AND `key` = :key LIMIT 1")
    suspend fun getValue(scriptId: Long, key: String): String?

    @Query("SELECT valueJson FROM gm_storage WHERE scriptId = :scriptId AND `key` = :key LIMIT 1")
    fun getValueSync(scriptId: Long, key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setValue(entity: GmStorageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setValueSync(entity: GmStorageEntity)

    @Query("DELETE FROM gm_storage WHERE scriptId = :scriptId AND `key` = :key")
    suspend fun deleteValue(scriptId: Long, key: String)

    @Query("DELETE FROM gm_storage WHERE scriptId = :scriptId AND `key` = :key")
    fun deleteValueSync(scriptId: Long, key: String)

    @Query("SELECT `key` FROM gm_storage WHERE scriptId = :scriptId ORDER BY `key` ASC")
    suspend fun listKeys(scriptId: Long): List<String>

    @Query("SELECT `key` FROM gm_storage WHERE scriptId = :scriptId ORDER BY `key` ASC")
    fun listKeysSync(scriptId: Long): List<String>

    @Query("SELECT * FROM gm_storage WHERE scriptId = :scriptId")
    suspend fun getAllForScript(scriptId: Long): List<GmStorageEntity>

    @Query("DELETE FROM gm_storage WHERE scriptId = :scriptId")
    suspend fun clearForScript(scriptId: Long)
}
