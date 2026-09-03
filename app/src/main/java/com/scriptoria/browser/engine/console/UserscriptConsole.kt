package com.scriptoria.browser.engine.console

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    LOG
}

data class ConsoleLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val scriptId: Long?,
    val scriptName: String,
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

object UserscriptConsole {

    private const val MAX_LOGS = 500

    private val _logs = MutableStateFlow<List<ConsoleLogEntry>>(emptyList())
    val logs: StateFlow<List<ConsoleLogEntry>> = _logs.asStateFlow()

    @Synchronized
    fun addLog(level: LogLevel, scriptId: Long?, scriptName: String, message: String) {
        val entry = ConsoleLogEntry(
            level = level,
            scriptId = scriptId,
            scriptName = scriptName,
            message = message
        )
        val current = _logs.value.toMutableList()
        if (current.size >= MAX_LOGS) {
            current.removeAt(0)
        }
        current.add(entry)
        _logs.value = current
    }

    @Synchronized
    fun clear() {
        _logs.value = emptyList()
    }
}
