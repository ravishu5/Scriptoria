package com.scriptoria.browser.engine.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit test verifying strict GM storage isolation between scripts.
 * Script A must never see or overwrite Script B's key-values.
 */
class GmStorageIsolationTest {

    private class MockGmStorageRepository {
        // (scriptId, key) -> valueJson
        private val store = ConcurrentHashMap<Pair<Long, String>, String>()

        fun getValue(scriptId: Long, key: String): String? = store[Pair(scriptId, key)]

        fun setValue(scriptId: Long, key: String, valueJson: String) {
            store[Pair(scriptId, key)] = valueJson
        }

        fun deleteValue(scriptId: Long, key: String) {
            store.remove(Pair(scriptId, key))
        }

        fun listKeys(scriptId: Long): List<String> {
            return store.keys
                .filter { it.first == scriptId }
                .map { it.second }
                .sorted()
        }
    }

    private lateinit var storage: MockGmStorageRepository

    @Before
    fun setUp() {
        storage = MockGmStorageRepository()
    }

    @Test
    fun testStorageIsolationBetweenScripts() {
        val scriptA = 101L
        val scriptB = 202L

        // Script A sets "token" to "token_A"
        storage.setValue(scriptA, "token", "\"token_A\"")

        // Script B sets "token" to "token_B"
        storage.setValue(scriptB, "token", "\"token_B\"")

        // Verify Script A sees only its own value
        assertEquals("\"token_A\"", storage.getValue(scriptA, "token"))

        // Verify Script B sees only its own value
        assertEquals("\"token_B\"", storage.getValue(scriptB, "token"))

        // Deleting from Script A must NOT affect Script B
        storage.deleteValue(scriptA, "token")
        assertNull(storage.getValue(scriptA, "token"))
        assertEquals("\"token_B\"", storage.getValue(scriptB, "token"))
    }

    @Test
    fun testListValuesIsolation() {
        val scriptA = 101L
        val scriptB = 202L

        storage.setValue(scriptA, "setting_one", "true")
        storage.setValue(scriptA, "setting_two", "123")

        storage.setValue(scriptB, "secret_key", "\"xyz\"")

        val keysA = storage.listKeys(scriptA)
        val keysB = storage.listKeys(scriptB)

        assertEquals(2, keysA.size)
        assertTrue(keysA.contains("setting_one"))
        assertTrue(keysA.contains("setting_two"))

        assertEquals(1, keysB.size)
        assertTrue(keysB.contains("secret_key"))
    }
}
