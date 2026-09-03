package com.scriptoria.browser.data.repository

import com.scriptoria.browser.data.database.ScriptDao
import com.scriptoria.browser.data.database.entities.ScriptEntity
import com.scriptoria.browser.data.storage.ScriptFileStore
import com.scriptoria.browser.engine.parser.RunAt
import com.scriptoria.browser.engine.parser.UserscriptMetadata
import com.scriptoria.browser.engine.parser.UserscriptParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

class ScriptRepository(
    private val scriptDao: ScriptDao,
    private val fileStore: ScriptFileStore
) {

    fun getAllFlow(): Flow<List<InstalledScript>> {
        return scriptDao.getAllFlow().map { entities ->
            entities.mapNotNull { entityToInstalled(it) }
        }
    }

    suspend fun getAll(): List<InstalledScript> {
        val entities = scriptDao.getAll()
        val list = mutableListOf<InstalledScript>()
        for (entity in entities) {
            val code = fileStore.readScriptCode(entity.id) ?: continue
            val reParsed = UserscriptParser.parse(code, entity.installUrl)
            if (entity.name == UserscriptParser.DEFAULT_NAME && reParsed.name != UserscriptParser.DEFAULT_NAME) {
                val updated = entity.copy(
                    name = reParsed.name,
                    namespace = reParsed.namespace,
                    version = reParsed.version,
                    description = reParsed.description,
                    author = reParsed.author,
                    matchesJson = listToJson(reParsed.matches),
                    includesJson = listToJson(reParsed.includes),
                    excludesJson = listToJson(reParsed.excludes),
                    grantsJson = listToJson(reParsed.grants),
                    requiresJson = listToJson(reParsed.requires),
                    connectsJson = listToJson(reParsed.connects),
                    runAt = reParsed.runAt.tagValue,
                    noFrames = reParsed.noFrames
                )
                scriptDao.update(updated)
                entityToInstalled(updated)?.let { list.add(it) }
            } else {
                entityToInstalled(entity)?.let { list.add(it) }
            }
        }
        return list
    }

    suspend fun getById(id: Long): InstalledScript? {
        val entity = scriptDao.getById(id) ?: return null
        return entityToInstalled(entity)
    }

    suspend fun addOrUpdate(code: String, sourceUrl: String? = null): Long {
        val metadata = UserscriptParser.parse(code, sourceUrl)

        // If an existing script shares the exact same name (not default), update it in place
        val existing = if (metadata.name != UserscriptParser.DEFAULT_NAME) {
            scriptDao.getByName(metadata.name)
        } else null

        if (existing != null) {
            fileStore.writeScriptCode(existing.id, code)
            val updatedEntity = existing.copy(
                namespace = metadata.namespace,
                version = metadata.version,
                description = metadata.description,
                author = metadata.author,
                matchesJson = listToJson(metadata.matches),
                includesJson = listToJson(metadata.includes),
                excludesJson = listToJson(metadata.excludes),
                grantsJson = listToJson(metadata.grants),
                requiresJson = listToJson(metadata.requires),
                connectsJson = listToJson(metadata.connects),
                runAt = metadata.runAt.tagValue,
                noFrames = metadata.noFrames,
                installUrl = sourceUrl ?: existing.installUrl,
                updateUrl = metadata.updateUrl ?: existing.updateUrl,
                downloadUrl = metadata.downloadUrl ?: existing.downloadUrl,
                lastUpdated = System.currentTimeMillis()
            )
            scriptDao.update(updatedEntity)
            return existing.id
        }

        val maxOrder = scriptDao.getMaxExecutionOrder() ?: 0
        val entity = ScriptEntity(
            name = metadata.name,
            namespace = metadata.namespace,
            version = metadata.version,
            description = metadata.description,
            author = metadata.author,
            enabled = true,
            matchesJson = listToJson(metadata.matches),
            includesJson = listToJson(metadata.includes),
            excludesJson = listToJson(metadata.excludes),
            grantsJson = listToJson(metadata.grants),
            requiresJson = listToJson(metadata.requires),
            connectsJson = listToJson(metadata.connects),
            runAt = metadata.runAt.tagValue,
            noFrames = metadata.noFrames,
            installUrl = sourceUrl,
            updateUrl = metadata.updateUrl,
            downloadUrl = metadata.downloadUrl,
            lastUpdated = System.currentTimeMillis(),
            lastExecuted = 0,
            executionOrder = maxOrder + 1
        )

        val id = scriptDao.insert(entity)
        fileStore.writeScriptCode(id, code)
        return id
    }

    suspend fun update(script: InstalledScript) {
        val metadata = UserscriptParser.parse(script.code, script.installUrl)
        fileStore.writeScriptCode(script.id, script.code)

        val entity = ScriptEntity(
            id = script.id,
            name = metadata.name,
            namespace = metadata.namespace,
            version = metadata.version,
            description = metadata.description,
            author = metadata.author,
            enabled = script.enabled,
            matchesJson = listToJson(metadata.matches),
            includesJson = listToJson(metadata.includes),
            excludesJson = listToJson(metadata.excludes),
            grantsJson = listToJson(metadata.grants),
            requiresJson = listToJson(metadata.requires),
            connectsJson = listToJson(metadata.connects),
            runAt = metadata.runAt.tagValue,
            noFrames = metadata.noFrames,
            installUrl = script.installUrl,
            updateUrl = metadata.updateUrl,
            downloadUrl = metadata.downloadUrl,
            lastUpdated = System.currentTimeMillis(),
            lastExecuted = script.lastExecuted,
            executionOrder = script.executionOrder
        )
        scriptDao.update(entity)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        scriptDao.setEnabled(id, enabled)
    }

    suspend fun delete(id: Long) {
        scriptDao.deleteById(id)
        fileStore.deleteScriptCode(id)
    }

    suspend fun updateLastExecuted(id: Long) {
        scriptDao.updateLastExecuted(id, System.currentTimeMillis())
    }

    private fun entityToInstalled(entity: ScriptEntity): InstalledScript? {
        val code = fileStore.readScriptCode(entity.id) ?: return null
        val metadata = UserscriptMetadata(
            name = entity.name,
            namespace = entity.namespace,
            version = entity.version,
            description = entity.description,
            author = entity.author,
            matches = jsonToList(entity.matchesJson),
            includes = jsonToList(entity.includesJson),
            excludes = jsonToList(entity.excludesJson),
            grants = jsonToList(entity.grantsJson),
            requires = jsonToList(entity.requiresJson),
            connects = jsonToList(entity.connectsJson),
            runAt = RunAt.fromString(entity.runAt),
            noFrames = entity.noFrames,
            installUrl = entity.installUrl,
            updateUrl = entity.updateUrl,
            downloadUrl = entity.downloadUrl
        )

        return InstalledScript(
            id = entity.id,
            metadata = metadata,
            code = code,
            enabled = entity.enabled,
            installUrl = entity.installUrl,
            lastUpdated = entity.lastUpdated,
            lastExecuted = entity.lastExecuted,
            executionOrder = entity.executionOrder
        )
    }

    private fun listToJson(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun jsonToList(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
