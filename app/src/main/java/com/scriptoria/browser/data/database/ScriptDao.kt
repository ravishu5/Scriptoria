package com.scriptoria.browser.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.scriptoria.browser.data.database.entities.ScriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptDao {

    @Query("SELECT * FROM userscripts ORDER BY executionOrder ASC, id ASC")
    fun getAllFlow(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM userscripts ORDER BY executionOrder ASC, id ASC")
    suspend fun getAll(): List<ScriptEntity>

    @Query("SELECT * FROM userscripts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ScriptEntity?

    @Query("SELECT * FROM userscripts WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(script: ScriptEntity): Long

    @Update
    suspend fun update(script: ScriptEntity)

    @Delete
    suspend fun delete(script: ScriptEntity)

    @Query("DELETE FROM userscripts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE userscripts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE userscripts SET lastExecuted = :timestamp WHERE id = :id")
    suspend fun updateLastExecuted(id: Long, timestamp: Long)

    @Query("SELECT MAX(executionOrder) FROM userscripts")
    suspend fun getMaxExecutionOrder(): Int?
}
