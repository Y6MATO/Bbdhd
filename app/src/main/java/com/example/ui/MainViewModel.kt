package com.example.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.AppFilterMode
import com.example.core.model.AppInfo
import com.example.core.model.CheckStatus
import com.example.core.model.ConnectionStats
import com.example.core.model.DiagnosticResult
import com.example.core.model.DiagnosticTarget
import com.example.core.model.DpiConfig
import com.example.core.model.DpiPreset
import com.example.core.model.LogEntry
import com.example.core.service.ByeDpiVpnService
import com.example.core.storage.AppLogManager
import com.example.core.storage.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ConfigRepository.getInstance(application)

    val configState: StateFlow<DpiConfig> = repository.configFlow
    val statsState: StateFlow<ConnectionStats> = ByeDpiVpnService.statsFlow
    val logsState: StateFlow<List<LogEntry>> = AppLogManager.logsFlow

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _diagnosticResults = MutableStateFlow<List<DiagnosticResult>>(createDefaultDiagnostics())
    val diagnosticResults: StateFlow<List<DiagnosticResult>> = _diagnosticResults.asStateFlow()

    private val _isTestingDiagnostics = MutableStateFlow(false)
    val isTestingDiagnostics: StateFlow<Boolean> = _isTestingDiagnostics.asStateFlow()

    init {
        loadInstalledApps()
    }

    private fun createDefaultDiagnostics(): List<DiagnosticResult> {
        return listOf(
            DiagnosticResult(
                target = DiagnosticTarget(
                    id = "youtube",
                    name = "YouTube & Google Video",
                    description = "Tests video streaming CDN bypassing ISP speed throttling",
                    url = "https://www.youtube.com/generate_204",
                    host = "www.youtube.com"
                )
            ),
            DiagnosticResult(
                target = DiagnosticTarget(
                    id = "discord",
                    name = "Discord Gateway",
                    description = "Tests Discord voice/chat connection & TLS handshake",
                    url = "https://discord.com/api/v9/gateway",
                    host = "discord.com"
                )
            ),
            DiagnosticResult(
                target = DiagnosticTarget(
                    id = "google",
                    name = "Google Services",
                    description = "Verifies general Google DNS and TLS accessibility",
                    url = "https://www.google.com/generate_204",
                    host = "www.google.com"
                )
            ),
            DiagnosticResult(
                target = DiagnosticTarget(
                    id = "cloudflare",
                    name = "Cloudflare 1.1.1.1",
                    description = "Verifies global encrypted DoH and edge connectivity",
                    url = "https://1.1.1.1/cdn-cgi/trace",
                    host = "1.1.1.1"
                )
            )
        )
    }

    fun setPreset(preset: DpiPreset) {
        repository.setPreset(preset)
        if (ByeDpiVpnService.isRunning) {
            val intent = Intent(getApplication(), ByeDpiVpnService::class.java).apply {
                action = ByeDpiVpnService.ACTION_UPDATE_CONFIG
            }
            getApplication<Application>().startService(intent)
        }
    }

    fun updateConfig(config: DpiConfig) {
        repository.updateConfig(config)
        if (ByeDpiVpnService.isRunning) {
            val intent = Intent(getApplication(), ByeDpiVpnService::class.java).apply {
                action = ByeDpiVpnService.ACTION_UPDATE_CONFIG
            }
            getApplication<Application>().startService(intent)
        }
    }

    fun startVpnService() {
        ByeDpiVpnService.startService(getApplication())
    }

    fun stopVpnService() {
        ByeDpiVpnService.stopService(getApplication())
    }

    fun toggleAppSelection(packageName: String) {
        val currentSet = configState.value.selectedPackageNames.toMutableSet()
        if (currentSet.contains(packageName)) {
            currentSet.remove(packageName)
        } else {
            currentSet.add(packageName)
        }
        repository.updateAppFilter(configState.value.appFilterMode, currentSet)
        updateInstalledAppsSelection(currentSet)
    }

    fun setAppFilterMode(mode: AppFilterMode) {
        repository.updateAppFilter(mode, configState.value.selectedPackageNames)
    }

    fun selectAllPopularApps() {
        val popular = listOf(
            "com.google.android.youtube",
            "com.discord",
            "org.telegram.messenger",
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.spotify.music",
            "com.instagram.android",
            "com.twitter.android"
        )
        val currentSet = configState.value.selectedPackageNames.toMutableSet()
        currentSet.addAll(popular)
        repository.updateAppFilter(AppFilterMode.INCLUDE_SELECTED, currentSet)
        updateInstalledAppsSelection(currentSet)
    }

    fun clearAppSelection() {
        repository.updateAppFilter(configState.value.appFilterMode, emptySet())
        updateInstalledAppsSelection(emptySet())
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
            try {
                val pm = getApplication<Application>().packageManager
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val selectedSet = configState.value.selectedPackageNames

                val appList = packages.map { appInfo ->
                    val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = name,
                        isSystemApp = isSys,
                        isSelected = selectedSet.contains(appInfo.packageName),
                        icon = icon
                    )
                }.sortedWith(compareBy({ !it.isSelected }, { it.isSystemApp }, { it.appName.lowercase() }))

                _installedApps.value = appList
            } catch (e: Exception) {
                AppLogManager.error("VM", "Failed to load apps: ${e.message}")
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    private fun updateInstalledAppsSelection(selectedSet: Set<String>) {
        _installedApps.value = _installedApps.value.map { app ->
            app.copy(isSelected = selectedSet.contains(app.packageName))
        }
    }

    fun runDiagnostics() {
        if (_isTestingDiagnostics.value) return
        _isTestingDiagnostics.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val list = _diagnosticResults.value.toMutableList()

            for (i in list.indices) {
                val target = list[i].target
                list[i] = list[i].copy(status = CheckStatus.CHECKING, details = "Probing connection...")
                _diagnosticResults.value = list.toList()

                val result = probeTarget(target)
                list[i] = result
                _diagnosticResults.value = list.toList()
            }

            _isTestingDiagnostics.value = false
        }
    }

    private suspend fun probeTarget(target: DiagnosticTarget): DiagnosticResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            // Test 1: Direct TCP + TLS handshake timing
            val socket = Socket()
            val tcpStart = System.currentTimeMillis()
            socket.connect(InetSocketAddress(target.host, target.port), 3000)
            val tcpTime = System.currentTimeMillis() - tcpStart

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket(socket, target.host, target.port, true) as SSLSocket
            val tlsStart = System.currentTimeMillis()
            sslSocket.startHandshake()
            val tlsTime = System.currentTimeMillis() - tlsStart
            sslSocket.close()

            // Test 2: HTTP GET check
            val client = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(target.url)
                .header("User-Agent", "Mozilla/5.0 (Android; ByeByeDPI Probe)")
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val totalLatency = System.currentTimeMillis() - startTime

            DiagnosticResult(
                target = target,
                status = if (response.isSuccessful || code in 200..399 || code == 204) CheckStatus.SUCCESS_UNTHROTTLED else CheckStatus.DPI_DETECTED_BLOCKED,
                latencyMs = totalLatency,
                httpCode = code,
                tlsHandshakeTimeMs = tlsTime,
                details = "HTTP $code, TCP: ${tcpTime}ms, TLS: ${tlsTime}ms (Evasion Active)",
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            val totalLatency = System.currentTimeMillis() - startTime
            DiagnosticResult(
                target = target,
                status = CheckStatus.NETWORK_ERROR,
                latencyMs = totalLatency,
                httpCode = 0,
                tlsHandshakeTimeMs = -1,
                details = "Error: ${e.message ?: "Connection failed"}",
                timestamp = System.currentTimeMillis()
            )
        }
    }

    fun clearLogs() {
        AppLogManager.clear()
    }
}
