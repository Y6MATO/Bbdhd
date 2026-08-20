package com.example.core.model

enum class CheckStatus {
    IDLE,
    CHECKING,
    SUCCESS_UNTHROTTLED,
    DPI_DETECTED_BLOCKED,
    NETWORK_ERROR
}

data class DiagnosticTarget(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val host: String,
    val port: Int = 443
)

data class DiagnosticResult(
    val target: DiagnosticTarget,
    val status: CheckStatus = CheckStatus.IDLE,
    val latencyMs: Long = -1L,
    val httpCode: Int = 0,
    val tlsHandshakeTimeMs: Long = -1L,
    val details: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
