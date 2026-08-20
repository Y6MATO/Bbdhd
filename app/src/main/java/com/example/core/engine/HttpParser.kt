package com.example.core.engine

import com.example.core.model.HttpModMode
import java.nio.charset.StandardCharsets
import java.util.Locale

object HttpParser {

    private val HTTP_METHODS = listOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "CONNECT", "TRACE", "PATCH")

    fun isHttpRequest(data: ByteArray, length: Int): Boolean {
        if (length < 4) return false
        val prefix = String(data, 0, length.coerceAtMost(10), StandardCharsets.US_ASCII)
        return HTTP_METHODS.any { prefix.startsWith("$it ") }
    }

    /**
     * Modifies HTTP request bytes to trick DPI filters (e.g. host header casing).
     */
    fun modifyHttpRequest(data: ByteArray, length: Int, mode: HttpModMode): ByteArray {
        if (mode == HttpModMode.NONE) return data.copyOf(length)

        val text = String(data, 0, length, StandardCharsets.ISO_8859_1)
        val lines = text.split("\r\n").toMutableList()

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("Host:", ignoreCase = true)) {
                val colonIdx = line.indexOf(':')
                if (colonIdx != -1) {
                    val value = line.substring(colonIdx + 1).trim()
                    lines[i] = when (mode) {
                        HttpModMode.LOWERCASE_HOST -> "host: $value"
                        HttpModMode.UPPERCASE_HOST -> "HOST: $value"
                        HttpModMode.SPACE_AFTER_HOST -> "Host:  $value"
                        HttpModMode.TAB_AFTER_HOST -> "Host:\t$value"
                        HttpModMode.NONE -> line
                    }
                }
            }
        }

        val modifiedText = lines.joinToString("\r\n")
        return modifiedText.toByteArray(StandardCharsets.ISO_8859_1)
    }
}
