package com.example.core.storage

import com.example.core.model.LogEntry
import com.example.core.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque

object AppLogManager {
    private const val MAX_LOGS = 500
    private val logQueue = ConcurrentLinkedDeque<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        logQueue.addFirst(entry)
        while (logQueue.size > MAX_LOGS) {
            logQueue.removeLast()
        }
        _logsFlow.value = logQueue.toList()
    }

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun evasion(tag: String, message: String) = log(LogLevel.DPI_EVASION, tag, message)
    fun dns(tag: String, message: String) = log(LogLevel.DNS, tag, message)
    fun tcp(tag: String, message: String) = log(LogLevel.TCP, tag, message)
    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun clear() {
        logQueue.clear()
        _logsFlow.value = emptyList()
    }
}
