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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.DnsProvider
import com.example.core.model.DpiConfig
import com.example.core.model.DpiPreset
import com.example.core.model.FakePacketMode
import com.example.core.model.HttpModMode
import com.example.core.model.SplitPosition
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.VioletSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsConfigScreen(
    config: DpiConfig,
    onUpdateConfig: (DpiConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }

    var expandedSplitDropdown by remember { mutableStateOf(false) }
    var expandedDnsDropdown by remember { mutableStateOf(false) }
    var expandedFakeDropdown by remember { mutableStateOf(false) }
    var expandedHttpDropdown by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        // Top Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DPI Evasion Modes",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Choose a preset or fine-tune fragmentation rules",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = "Export Config",
                            tint = CyanPrimary
                        )
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Import Config",
                            tint = VioletSecondary
                        )
                    }
                }
            }
        }

        // 1. Presets Selector List
        item {
            Text(
                text = "Preset Profiles",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        items(DpiPreset.values()) { preset ->
            val isSelected = config.preset == preset
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onUpdateConfig(DpiConfig.defaultForPreset(preset).copy(
                            appFilterMode = config.appFilterMode,
                            selectedPackageNames = config.selectedPackageNames,
                            socksPort = config.socksPort
                        ))
                    }
                    .testTag("preset_card_${preset.id}"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) CyanPrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (isSelected) CyanPrimary else MaterialTheme.colorScheme.outline
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = when (preset) {
                            DpiPreset.YOUTUBE_FIX -> Icons.Default.PlayArrow
                            DpiPreset.DISCORD_FIX -> Icons.Default.Bolt
                            DpiPreset.STANDARD -> Icons.Default.Shield
                            DpiPreset.AGGRESSIVE -> Icons.Default.Speed
                            DpiPreset.CUSTOM -> Icons.Default.Tune
                        },
                        contentDescription = null,
                        tint = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.displayName,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    RadioButton(
                        selected = isSelected,
                        onClick = {
                            onUpdateConfig(DpiConfig.defaultForPreset(preset).copy(
                                appFilterMode = config.appFilterMode,
                                selectedPackageNames = config.selectedPackageNames,
                                socksPort = config.socksPort
                            ))
                        }
                    )
                }
            }
        }

        // 2. Fine-grained Parameters
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Advanced Desync Parameters",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Split Position Selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "TLS/TCP Split Position",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        ExposedDropdownMenuBox(
                            expanded = expandedSplitDropdown,
                            onExpandedChange = { expandedSplitDropdown = !expandedSplitDropdown }
                        ) {
                            OutlinedTextField(
                                value = config.splitPosition.label,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSplitDropdown) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = expandedSplitDropdown,
                                onDismissRequest = { expandedSplitDropdown = false }
                            ) {
                                SplitPosition.values().forEach { pos ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(pos.label, fontWeight = FontWeight.Bold)
                                                Text(pos.description, style = MaterialTheme.typography.bodySmall)
                                            }
                                        },
                                        onClick = {
                                            onUpdateConfig(config.copy(splitPosition = pos, preset = DpiPreset.CUSTOM))
                                            expandedSplitDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Custom Split Offset (if custom)
                    if (config.splitPosition == SplitPosition.CUSTOM_OFFSET) {
                        Column {
                            Text(
                                text = "Split Offset: ${config.customSplitOffset} bytes",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Slider(
                                value = config.customSplitOffset.toFloat(),
                                onValueChange = {
                                    onUpdateConfig(config.copy(customSplitOffset = it.toInt(), preset = DpiPreset.CUSTOM))
                                },
                                valueRange = 1f..100f,
                                steps = 99
                            )
                        }
                    }

                    // Segment Delay Slider
                    Column {
                        Text(
                            text = "Inter-Segment Delay: ${config.segmentDelayMs} ms",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Sleep time between fragments to defeat TCP reassembly",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = config.segmentDelayMs.toFloat(),
                            onValueChange = {
                                onUpdateConfig(config.copy(segmentDelayMs = it.toLong(), preset = DpiPreset.CUSTOM))
                            },
                            valueRange = 0f..50f,
                            steps = 50
                        )
                    }

                    // Fake Packet Decoy Mode
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Fake / Decoy Packet Injection",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        ExposedDropdownMenuBox(
                            expanded = expandedFakeDropdown,
                            onExpandedChange = { expandedFakeDropdown = !expandedFakeDropdown }
                        ) {
                            OutlinedTextField(
                                value = config.fakePacketMode.label,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFakeDropdown) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = expandedFakeDropdown,
                                onDismissRequest = { expandedFakeDropdown = false }
                            ) {
                                FakePacketMode.values().forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(mode.label, fontWeight = FontWeight.Bold)
                                                Text(mode.description, style = MaterialTheme.typography.bodySmall)
                                            }
                                        },
                                        onClick = {
                                            onUpdateConfig(config.copy(fakePacketMode = mode, preset = DpiPreset.CUSTOM))
                                            expandedFakeDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // HTTP Header Mod
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "HTTP Header Modification",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        ExposedDropdownMenuBox(
                            expanded = expandedHttpDropdown,
                            onExpandedChange = { expandedHttpDropdown = !expandedHttpDropdown }
                        ) {
                            OutlinedTextField(
                                value = config.httpModMode.label,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedHttpDropdown) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = expandedHttpDropdown,
                                onDismissRequest = { expandedHttpDropdown = false }
                            ) {
                                HttpModMode.values().forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        onClick = {
                                            onUpdateConfig(config.copy(httpModMode = mode, preset = DpiPreset.CUSTOM))
                                            expandedHttpDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // DNS-over-HTTPS Provider
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Encrypted DNS (DoH Resolver)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        ExposedDropdownMenuBox(
                            expanded = expandedDnsDropdown,
                            onExpandedChange = { expandedDnsDropdown = !expandedDnsDropdown }
                        ) {
                            OutlinedTextField(
                                value = config.dnsProvider.displayName,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDnsDropdown) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = expandedDnsDropdown,
                                onDismissRequest = { expandedDnsDropdown = false }
                            ) {
                                DnsProvider.values().forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider.displayName) },
                                        onClick = {
                                            onUpdateConfig(config.copy(dnsProvider = provider, preset = DpiPreset.CUSTOM))
                                            expandedDnsDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Drop QUIC Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Drop UDP QUIC / HTTP3",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "Forces apps to fallback to TCP where DPI desync takes effect",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.blockUdpQuic,
                            onCheckedChange = {
                                onUpdateConfig(config.copy(blockUdpQuic = it, preset = DpiPreset.CUSTOM))
                            }
                        )
                    }
                }
            }
        }

        // 3. Command Line CLI Equivalent Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Code, contentDescription = null, tint = CyanPrimary)
                            Text(
                                text = "ByeDPI CLI Equivalent",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(config.toCommandLineArgs()))
                                Toast.makeText(context, "CLI args copied to clipboard!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy CLI",
                                tint = CyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "byedpi ${config.toCommandLineArgs()}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = CyanPrimary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }

    // Export Dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Profile Configuration") },
            text = {
                OutlinedTextField(
                    value = config.toJson(),
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(config.toJson()))
                        Toast.makeText(context, "Config JSON copied!", Toast.LENGTH_SHORT).show()
                        showExportDialog = false
                    }
                ) {
                    Text("Copy JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Import Dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Profile JSON") },
            text = {
                OutlinedTextField(
                    value = importJsonText,
                    onValueChange = { importJsonText = it },
                    placeholder = { Text("Paste configuration JSON here...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (importJsonText.isNotBlank()) {
                            val parsed = DpiConfig.fromJson(importJsonText)
                            onUpdateConfig(parsed)
                            Toast.makeText(context, "Config loaded successfully!", Toast.LENGTH_SHORT).show()
                            showImportDialog = false
                        }
                    }
                ) {
                    Text("Apply Config")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
