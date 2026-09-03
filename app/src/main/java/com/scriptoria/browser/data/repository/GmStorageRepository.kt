package com.scriptoria.browser.data.repository

import com.scriptoria.browser.data.database.GmStorageDao
import com.scriptoria.browser.data.database.entities.GmStorageEntity

/**
 * Isolated GM key-value store partitioned by [scriptId].
 * Scripts cannot read or write another script's key-values.
 */
class GmStorageRepository(
    private val gmStorageDao: GmStorageDao
) {

    fun getValue(scriptId: Long, key: String): String? {
        return gmStorageDao.getValueSync(scriptId, key)
    }

    fun setValue(scriptId: Long, key: String, valueJson: String) {
        gmStorageDao.setValueSync(GmStorageEntity(scriptId, key, valueJson))
    }

    fun deleteValue(scriptId: Long, key: String) {
        gmStorageDao.deleteValueSync(scriptId, key)
    }

    fun listKeys(scriptId: Long): List<String> {
        return gmStorageDao.listKeysSync(scriptId)
    }

    suspend fun clearForScript(scriptId: Long) {
        gmStorageDao.clearForScript(scriptId)
    }

    suspend fun getAllForScript(scriptId: Long): Map<String, String> {
        return gmStorageDao.getAllForScript(scriptId).associate { it.key to it.valueJson }
    }
}
