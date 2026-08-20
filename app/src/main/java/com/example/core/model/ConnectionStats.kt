package com.example.core.model

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STOPPING,
    ERROR
}

data class ConnectionStats(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
    val speedInBps: Long = 0L,
    val speedOutBps: Long = 0L,
    val activeConnections: Int = 0,
    val totalConnectionsHandled: Long = 0L,
    val connectedSinceTimestamp: Long = 0L,
    val currentPreset: DpiPreset = DpiPreset.YOUTUBE_FIX,
    val lastEvasionType: String = "",
    val errorMessage: String? = null
) {
    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format("%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    fun formatSpeed(speedBps: Long): String {
        return when {
            speedBps >= 1024 * 1024 -> String.format("%.1f MB/s", speedBps.toDouble() / (1024 * 1024))
            speedBps >= 1024 -> String.format("%.1f KB/s", speedBps.toDouble() / 1024)
            else -> "$speedBps B/s"
        }
    }

    fun formatDuration(): String {
        if (connectedSinceTimestamp == 0L || status != VpnStatus.CONNECTED) return "00:00:00"
        val seconds = ((System.currentTimeMillis() - connectedSinceTimestamp) / 1000).coerceAtLeast(0)
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hrs, mins, secs)
    }
}
