package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.VpnStatus
import com.example.ui.MainViewModel
import com.example.ui.screens.AppFilterScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DiagnosticsLogsScreen
import com.example.ui.screens.PresetsConfigScreen
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnService()
        } else {
            Toast.makeText(this, "VPN permission is required to bypass DPI", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Notification permission status handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyApplicationTheme(darkTheme = true) {
                MainApp(
                    viewModel = viewModel,
                    onToggleVpn = { handleVpnToggle() }
                )
            }
        }
    }

    private fun handleVpnToggle() {
        val stats = viewModel.statsState.value
        if (stats.status == VpnStatus.CONNECTED || stats.status == VpnStatus.CONNECTING) {
            viewModel.stopVpnService()
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                viewModel.startVpnService()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    viewModel: MainViewModel,
    onToggleVpn: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val config by viewModel.configState.collectAsStateWithLifecycle()
    val stats by viewModel.statsState.collectAsStateWithLifecycle()
    val logs by viewModel.logsState.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnosticResults.collectAsStateWithLifecycle()
    val isTestingDiagnostics by viewModel.isTestingDiagnostics.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            0 -> "ByeByeDPI"
                            1 -> "Evasion Config"
                            2 -> "App Routing"
                            else -> "Diagnostics & Logs"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("bottom_navigation_bar"),
                containerColor = DarkBackground
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        indicatorColor = CyanPrimary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "Modes") },
                    label = { Text("Modes") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        indicatorColor = CyanPrimary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Apps, contentDescription = "Apps") },
                    label = { Text("Apps") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        indicatorColor = CyanPrimary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Assessment, contentDescription = "Diagnostics") },
                    label = { Text("Diagnostics") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        indicatorColor = CyanPrimary
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    stats = stats,
                    config = config,
                    diagnostics = diagnostics,
                    onToggleVpn = onToggleVpn,
                    onSelectPreset = { viewModel.setPreset(it) },
                    onNavigateToDiagnostics = { selectedTab = 3 },
                    onNavigateToConfig = { selectedTab = 1 }
                )
                1 -> PresetsConfigScreen(
                    config = config,
                    onUpdateConfig = { viewModel.updateConfig(it) }
                )
                2 -> AppFilterScreen(
                    currentMode = config.appFilterMode,
                    installedApps = installedApps,
                    isLoading = isLoadingApps,
                    onSetMode = { viewModel.setAppFilterMode(it) },
                    onToggleApp = { viewModel.toggleAppSelection(it) },
                    onSelectPopular = { viewModel.selectAllPopularApps() },
                    onClearSelection = { viewModel.clearAppSelection() }
                )
                3 -> DiagnosticsLogsScreen(
                    diagnostics = diagnostics,
                    isTesting = isTestingDiagnostics,
                    onRunDiagnostics = { viewModel.runDiagnostics() },
                    logs = logs,
                    onClearLogs = { viewModel.clearLogs() }
                )
            }
        }
    }
}
