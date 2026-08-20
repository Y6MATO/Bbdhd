package com.example.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.engine.DpiDesyncSocketForwarder
import com.example.core.engine.LocalSocks5Server
import com.example.core.engine.TunRouter
import com.example.core.model.AppFilterMode
import com.example.core.model.ConnectionStats
import com.example.core.model.DpiConfig
import com.example.core.model.VpnStatus
import com.example.core.storage.AppLogManager
import com.example.core.storage.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ByeDpiVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.byebyedpi.ACTION_START"
        const val ACTION_STOP = "com.example.byebyedpi.ACTION_STOP"
        const val ACTION_UPDATE_CONFIG = "com.example.byebyedpi.ACTION_UPDATE_CONFIG"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "byebyedpi_vpn_status"

        private val _statsFlow = MutableStateFlow(ConnectionStats())
        val statsFlow: StateFlow<ConnectionStats> = _statsFlow.asStateFlow()

        var isRunning = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, ByeDpiVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ByeDpiVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var statsJob: Job? = null

    private var vpnInterface: ParcelFileDescriptor? = null
    private var socksServer: LocalSocks5Server? = null
    private var tunRouter: TunRouter? = null
    private var currentConfig: DpiConfig = DpiConfig()
    private var connectTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startVpn()
            }
            ACTION_STOP -> {
                stopVpn()
            }
            ACTION_UPDATE_CONFIG -> {
                reloadConfig()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        isRunning = true
        connectTime = System.currentTimeMillis()

        val repository = ConfigRepository.getInstance(this)
        currentConfig = repository.configFlow.value

        _statsFlow.value = ConnectionStats(
            status = VpnStatus.CONNECTING,
            currentPreset = currentConfig.preset,
            connectedSinceTimestamp = connectTime
        )

        startForeground(NOTIFICATION_ID, buildNotification("Connecting DPI Bypass...", "Setting up tunnel"))

        serviceScope.launch(Dispatchers.IO) {
            try {
                AppLogManager.info("VPN", "Starting ByeByeDPI service (${currentConfig.preset.displayName})")

                // Start SOCKS5 Server
                if (currentConfig.enableSocksServer) {
                    socksServer = LocalSocks5Server(currentConfig, this@ByeDpiVpnService).also {
                        it.start(serviceScope)
                    }
                }

                // Build and establish TUN Interface
                if (currentConfig.enableVpn) {
                    val builder = Builder()
                        .setSession("ByeByeDPI")
                        .setMtu(1500)
                        .addAddress("10.11.12.1", 24)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("10.11.12.1") // Local DNS sink intercepted by TunRouter

                    // Split Tunneling / App filtering
                    when (currentConfig.appFilterMode) {
                        AppFilterMode.INCLUDE_SELECTED -> {
                            currentConfig.selectedPackageNames.forEach { pkg ->
                                runCatching { builder.addAllowedApplication(pkg) }
                            }
                        }
                        AppFilterMode.EXCLUDE_SELECTED -> {
                            currentConfig.selectedPackageNames.forEach { pkg ->
                                runCatching { builder.addDisallowedApplication(pkg) }
                            }
                            runCatching { builder.addDisallowedApplication(packageName) }
                        }
                        AppFilterMode.ALL_APPS -> {
                            runCatching { builder.addDisallowedApplication(packageName) }
                        }
                    }

                    vpnInterface = builder.establish()

                    if (vpnInterface != null) {
                        tunRouter = TunRouter(vpnInterface!!, this@ByeDpiVpnService, currentConfig).also {
                            it.start(serviceScope)
                        }
                    } else {
                        AppLogManager.error("VPN", "Failed to establish VPN interface descriptor")
                    }
                }

                _statsFlow.value = ConnectionStats(
                    status = VpnStatus.CONNECTED,
                    currentPreset = currentConfig.preset,
                    connectedSinceTimestamp = connectTime
                )

                updateNotification()
                startStatsLoop()

            } catch (e: Exception) {
                AppLogManager.error("VPN", "Failed to start VPN: ${e.message}")
                _statsFlow.value = ConnectionStats(
                    status = VpnStatus.ERROR,
                    errorMessage = e.message
                )
                stopVpn()
            }
        }
    }

    private fun reloadConfig() {
        val repository = ConfigRepository.getInstance(this)
        currentConfig = repository.configFlow.value
        AppLogManager.info("VPN", "Reloaded configuration: ${currentConfig.preset.displayName}")
        updateNotification()
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch(Dispatchers.Default) {
            var lastIn = 0L
            var lastOut = 0L
            var lastTime = System.currentTimeMillis()

            while (isActive && isRunning) {
                delay(1000)
                val curIn = DpiDesyncSocketForwarder.totalBytesIn.get()
                val curOut = DpiDesyncSocketForwarder.totalBytesOut.get()
                val curTime = System.currentTimeMillis()
                val dt = (curTime - lastTime).coerceAtLeast(1) / 1000.0

                val speedIn = ((curIn - lastIn) / dt).toLong().coerceAtLeast(0)
                val speedOut = ((curOut - lastOut) / dt).toLong().coerceAtLeast(0)

                lastIn = curIn
                lastOut = curOut
                lastTime = curTime

                val currentStats = ConnectionStats(
                    status = VpnStatus.CONNECTED,
                    bytesIn = curIn,
                    bytesOut = curOut,
                    speedInBps = speedIn,
                    speedOutBps = speedOut,
                    activeConnections = DpiDesyncSocketForwarder.activeConnections.get().toInt(),
                    totalConnectionsHandled = DpiDesyncSocketForwarder.totalConnections.get(),
                    connectedSinceTimestamp = connectTime,
                    currentPreset = currentConfig.preset
                )
                _statsFlow.value = currentStats

                updateNotification()
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        statsJob?.cancel()

        serviceScope.launch(Dispatchers.IO) {
            AppLogManager.info("VPN", "Stopping ByeByeDPI service...")
            tunRouter?.stop()
            tunRouter = null

            socksServer?.stop()
            socksServer = null

            runCatching { vpnInterface?.close() }
            vpnInterface = null

            _statsFlow.value = ConnectionStats(
                status = VpnStatus.DISCONNECTED
            )

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ByeByeDPI Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live connection status and throughput of ByeByeDPI"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, ByeDpiVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Disconnect", disconnectPendingIntent)
            .build()
    }

    private fun updateNotification() {
        if (!isRunning) return
        val stats = _statsFlow.value
        val title = "DPI Bypass Active (${stats.currentPreset.displayName})"
        val content = "↓ ${stats.formatSpeed(stats.speedInBps)}  ↑ ${stats.formatSpeed(stats.speedOutBps)} | ${stats.formatDuration()}"

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }
}
