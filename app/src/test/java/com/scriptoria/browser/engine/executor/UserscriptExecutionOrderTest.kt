package com.scriptoria.browser.engine.executor

import com.scriptoria.browser.data.repository.InstalledScript
import com.scriptoria.browser.engine.matcher.UrlMatcher
import com.scriptoria.browser.engine.parser.RunAt
import com.scriptoria.browser.engine.parser.UserscriptMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserscriptExecutionOrderTest {

    @Test
    fun testScriptOrderingAndRunAtLifecycle() {
        val scriptEarly = InstalledScript(
            id = 1L,
            metadata = UserscriptMetadata(
                name = "Early Hook",
                matches = listOf("https://example.com/*"),
                runAt = RunAt.DOCUMENT_START
            ),
            code = "console.log('early');",
            enabled = true,
            executionOrder = 1
        )

        val scriptMain1 = InstalledScript(
            id = 2L,
            metadata = UserscriptMetadata(
                name = "Main Tool A",
                matches = listOf("https://example.com/*"),
                runAt = RunAt.DOCUMENT_END
            ),
            code = "console.log('main 1');",
            enabled = true,
            executionOrder = 1
        )

        val scriptMain2 = InstalledScript(
            id = 3L,
            metadata = UserscriptMetadata(
                name = "Main Tool B",
                matches = listOf("https://example.com/*"),
                runAt = RunAt.DOCUMENT_END
            ),
            code = "console.log('main 2');",
            enabled = true,
            executionOrder = 2
        )

        val disabledScript = InstalledScript(
            id = 4L,
            metadata = UserscriptMetadata(
                name = "Disabled Script",
                matches = listOf("https://example.com/*"),
                runAt = RunAt.DOCUMENT_END
            ),
            code = "console.log('disabled');",
            enabled = false,
            executionOrder = 0
        )

        val allScripts = listOf(scriptMain2, scriptEarly, disabledScript, scriptMain1)
        val targetUrl = "https://example.com/page"

        // Filter for DOCUMENT_START
        val startScripts = allScripts.filter { s ->
            s.enabled && s.metadata.runAt == RunAt.DOCUMENT_START &&
                    UrlMatcher.matches(targetUrl, s.metadata.matches, s.metadata.includes, s.metadata.excludes)
        }.sortedBy { it.executionOrder }

        assertEquals(1, startScripts.size)
        assertEquals("Early Hook", startScripts[0].metadata.name)

        // Filter for DOCUMENT_END
        val endScripts = allScripts.filter { s ->
            s.enabled && s.metadata.runAt == RunAt.DOCUMENT_END &&
                    UrlMatcher.matches(targetUrl, s.metadata.matches, s.metadata.includes, s.metadata.excludes)
        }.sortedBy { it.executionOrder }

        assertEquals(2, endScripts.size)
        assertEquals("Main Tool A", endScripts[0].metadata.name)
        assertEquals("Main Tool B", endScripts[1].metadata.name)
    }
}
