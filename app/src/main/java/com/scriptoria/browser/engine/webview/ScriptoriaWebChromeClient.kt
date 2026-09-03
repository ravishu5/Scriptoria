package com.scriptoria.browser.engine.webview

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.scriptoria.browser.engine.console.LogLevel
import com.scriptoria.browser.engine.console.UserscriptConsole

class ScriptoriaWebChromeClient(
    private val onProgressChanged: (progress: Int) -> Unit,
    private val onTitleReceived: (title: String) -> Unit
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChanged(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        if (!title.isNullOrBlank()) {
            onTitleReceived(title)
        }
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        if (consoleMessage == null) return super.onConsoleMessage(consoleMessage)

        val level = when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> LogLevel.ERROR
            ConsoleMessage.MessageLevel.WARNING -> LogLevel.WARN
            else -> LogLevel.INFO
        }

        val text = consoleMessage.message()
        val sourceId = consoleMessage.sourceId() ?: ""
        val line = consoleMessage.lineNumber()

        // Extract script name if it comes from our userscript sourceURL
        val scriptName = if (sourceId.contains("scriptoria-")) {
            sourceId.substringAfter("scriptoria-").substringBefore(".user.js")
        } else {
            "Page Console"
        }

        UserscriptConsole.addLog(
            level = level,
            scriptId = null,
            scriptName = scriptName,
            message = "$text ($sourceId:$line)"
        )

        return super.onConsoleMessage(consoleMessage)
    }
}
