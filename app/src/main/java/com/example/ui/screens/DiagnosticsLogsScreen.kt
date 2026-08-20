package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.CheckStatus
import com.example.core.model.DiagnosticResult
import com.example.core.model.LogEntry
import com.example.core.model.LogLevel
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.VioletSecondary

@Composable
fun DiagnosticsLogsScreen(
    diagnostics: List<DiagnosticResult>,
    isTesting: Boolean,
    onRunDiagnostics: () -> Unit,
    logs: List<LogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(null) }

    val filteredLogs = remember(logs, selectedLogLevel) {
        if (selectedLogLevel == null) logs else logs.filter { it.level == selectedLogLevel }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Tab Selection: Diagnostics vs Live Logs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = CyanPrimary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Connectivity & DPI Test", fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Live Event Logs", fontWeight = FontWeight.SemiBold) }
            )
        }

        if (selectedTab == 0) {
            // Diagnostics Screen
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
            ) {
                item {
                    Button(
                        onClick = onRunDiagnostics,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("run_diagnostics_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        enabled = !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing Targets...", color = Color.White)
                        } else {
                            Icon(imageVector = Icons.Default.NetworkCheck, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Run Full Circumvention Test", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                items(diagnostics) { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = result.target.name,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = result.target.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when (result.status) {
                                        CheckStatus.SUCCESS_UNTHROTTLED -> StatusGreen.copy(alpha = 0.15f)
                                        CheckStatus.DPI_DETECTED_BLOCKED -> StatusRed.copy(alpha = 0.15f)
                                        CheckStatus.CHECKING -> CyanPrimary.copy(alpha = 0.15f)
                                        CheckStatus.NETWORK_ERROR -> StatusRed.copy(alpha = 0.15f)
                                        CheckStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        when (result.status) {
                                            CheckStatus.SUCCESS_UNTHROTTLED -> {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = StatusGreen,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = "BYPASSED (${result.latencyMs}ms)",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = StatusGreen
                                                )
                                            }
                                            CheckStatus.DPI_DETECTED_BLOCKED -> {
                                                Icon(
                                                    imageVector = Icons.Default.Error,
                                                    contentDescription = null,
                                                    tint = StatusRed,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = "THROTTLED / BLOCKED",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = StatusRed
                                                )
                                            }
                                            CheckStatus.CHECKING -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(12.dp),
                                                    color = CyanPrimary,
                                                    strokeWidth = 2.dp
                                                )
                                                Text(
                                                    text = "PROBING...",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = CyanPrimary
                                                )
                                            }
                                            else -> {
                                                Text(
                                                    text = "READY",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (result.details.isNotBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = result.details,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Live Logs Screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header & Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Logs (${filteredLogs.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )

                    Row {
                        IconButton(
                            onClick = {
                                val allText = logs.joinToString("\n") { "[${it.formatTime()}][${it.level}] ${it.tag}: ${it.message}" }
                                clipboardManager.setText(AnnotatedString(allText))
                                Toast.makeText(context, "Logs copied!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy Logs", tint = CyanPrimary)
                        }
                        IconButton(onClick = onClearLogs) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear Logs", tint = StatusRed)
                        }
                    }
                }

                // Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedLogLevel == null,
                        onClick = { selectedLogLevel = null },
                        label = { Text("ALL") }
                    )
                    FilterChip(
                        selected = selectedLogLevel == LogLevel.DPI_EVASION,
                        onClick = { selectedLogLevel = LogLevel.DPI_EVASION },
                        label = { Text("DPI") }
                    )
                    FilterChip(
                        selected = selectedLogLevel == LogLevel.DNS,
                        onClick = { selectedLogLevel = LogLevel.DNS },
                        label = { Text("DNS") }
                    )
                    FilterChip(
                        selected = selectedLogLevel == LogLevel.TCP,
                        onClick = { selectedLogLevel = LogLevel.TCP },
                        label = { Text("TCP") }
                    )
                }

                // Console Container
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF070B13),
                    border = BorderStrokeOrNull(MaterialTheme.colorScheme.outline)
                ) {
                    if (filteredLogs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No logs yet. Start the bypass to view live events.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredLogs, key = { it.id }) { log ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = log.formatTime(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        ),
                                        color = Color(0xFF64748B)
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = when (log.level) {
                                            LogLevel.DPI_EVASION -> CyanPrimary.copy(alpha = 0.2f)
                                            LogLevel.DNS -> VioletSecondary.copy(alpha = 0.2f)
                                            LogLevel.TCP -> EmeraldTertiary.copy(alpha = 0.2f)
                                            LogLevel.ERROR -> StatusRed.copy(alpha = 0.2f)
                                            LogLevel.WARN -> Color(0xFFF59E0B).copy(alpha = 0.2f)
                                            else -> Color(0xFF334155)
                                        }
                                    ) {
                                        Text(
                                            text = log.level.name.take(3),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            ),
                                            color = when (log.level) {
                                                LogLevel.DPI_EVASION -> CyanPrimary
                                                LogLevel.DNS -> VioletSecondary
                                                LogLevel.TCP -> EmeraldTertiary
                                                LogLevel.ERROR -> StatusRed
                                                LogLevel.WARN -> Color(0xFFF59E0B)
                                                else -> Color.White
                                            },
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }

                                    Text(
                                        text = log.message,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        ),
                                        color = Color(0xFFE2E8F0),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BorderStrokeOrNull(color: Color): androidx.compose.foundation.BorderStroke {
    return androidx.compose.foundation.BorderStroke(1.dp, color)
}
