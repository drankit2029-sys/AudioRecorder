package com.studio.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studio.audio.core.audio.AudioInputDevice

@Composable
fun StudioScreen(viewModel: StudioViewModel = viewModel()) {
    val devices by viewModel.availableDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    var showDeviceDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshDevices()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            PersistentDock(
                isRecording = isRecording,
                onFabClick = { viewModel.toggleRecording() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(innerPadding)
        ) {
            // 1. Teleprompter Container
            SectionPlaceholder(
                title = "1. Teleprompter Container",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.8f)
            )

            // 2. Waveform Visualizer
            SectionPlaceholder(
                title = "2. Waveform Visualizer",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.2f)
            )

            // 3. Control & Metrics Strip (with Device Selection Pill)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.8f)
                    .padding(8.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = if (isRecording) "RECORDING ACTIVE (Writing raw PCM)" else "STANDBY",
                    color = if (isRecording) Color(0xFFE53935) else Color.Gray,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Blueprint Row 1: Input Hardware Pill Button
                Row(modifier = Modifier.fillMaxWidth()) {
                    InputHardwarePill(
                        deviceName = selectedDevice?.name ?: "Detect Mic",
                        onClick = {
                            viewModel.refreshDevices()
                            showDeviceDialog = true
                        }
                    )
                }
            }
        }
    }

    if (showDeviceDialog) {
        DeviceSelectionDialog(
            devices = devices,
            currentDevice = selectedDevice,
            onDeviceSelected = {
                viewModel.selectDevice(it)
                showDeviceDialog = false
            },
            onDismiss = { showDeviceDialog = false }
        )
    }
}

@Composable
private fun InputHardwarePill(deviceName: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        color = Color(0xFF2C2C2E),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A3A3C))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = Color(0xFFE53935),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "🎙 $deviceName",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DeviceSelectionDialog(
    devices: List<AudioInputDevice>,
    currentDevice: AudioInputDevice?,
    onDeviceSelected: (AudioInputDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Available Audio Inputs") },
        text = {
            if (devices.isEmpty()) {
                Text("No input devices detected.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(devices) { device ->
                        val isSelected = device.id == currentDevice?.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceSelected(device) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = "${device.name} (${device.typeLabel})",
                                color = if (isSelected) Color(0xFF1E88E5) else Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Supported: ${device.sampleRates.joinToString { "${it}Hz" }}",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun PersistentDock(
    isRecording: Boolean,
    onFabClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = Color(0xFF161616),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {}) { Text("Library", color = Color.White) }

            FloatingActionButton(
                onClick = onFabClick,
                containerColor = if (isRecording) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop" else "Record"
                )
            }

            TextButton(onClick = {}) { Text("Studio", color = Color.White) }
        }
    }
}

@Composable
private fun SectionPlaceholder(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .background(Color(0xFF1C1C1E), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = Color.LightGray
        )
    }
}