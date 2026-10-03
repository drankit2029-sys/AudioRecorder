package com.studio.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studio.audio.core.audio.AudioInputDevice
import com.studio.audio.core.audio.AudioPreset
import com.studio.audio.core.audio.InterruptedSession
import com.studio.audio.ui.components.DeviceSelectionDialog
import com.studio.audio.ui.components.PresetSelectionDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StudioScreen(viewModel: StudioViewModel = viewModel()) {
    val destination by viewModel.currentDestination.collectAsState()
    val devices by viewModel.availableDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val selectedPreset by viewModel.selectedPreset.collectAsState()
    val customPreset by viewModel.customPreset.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val interruptedSession by viewModel.interruptedSession.collectAsState()
    val pendingSaveFile by viewModel.pendingSaveFile.collectAsState()
    val conversionProgress by viewModel.conversionProgress.collectAsState()
    val savedRecordings by viewModel.savedRecordings.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val isPlayingAudio by viewModel.isPlayingAudio.collectAsState()
    val currentPlayingFile by viewModel.currentPlayingFile.collectAsState()
    val playbackPositionMs by viewModel.playbackPositionMs.collectAsState()
    val playbackDurationMs by viewModel.playbackDurationMs.collectAsState()

    var showDeviceDialog by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            PersistentDock(
                currentDestination = destination,
                isRecording = isRecording,
                onLibraryClick = { viewModel.navigateTo(AppDestination.LIBRARY) },
                onStudioClick = { viewModel.navigateTo(AppDestination.STUDIO) },
                onFabClick = {
                    if (destination != AppDestination.STUDIO) {
                        viewModel.navigateTo(AppDestination.STUDIO)
                    }
                    viewModel.toggleRecording()
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(innerPadding)
        ) {
            when (destination) {
                AppDestination.STUDIO -> {
                    StudioContent(
                        isRecording = isRecording,
                        selectedDevice = selectedDevice,
                        selectedPreset = selectedPreset,
                        onOpenDeviceSelector = {
                            if (!isRecording) {
                                viewModel.refreshDevices()
                                showDeviceDialog = true
                            }
                        },
                        onOpenPresetSelector = {
                            if (!isRecording) {
                                showPresetDialog = true
                            }
                        }
                    )
                }
                AppDestination.LIBRARY -> {
                    LibraryScreen(
                        recordings = savedRecordings,
                        currentPlayingFile = currentPlayingFile,
                        isPlaying = isPlayingAudio,
                        playbackPositionMs = playbackPositionMs,
                        playbackDurationMs = playbackDurationMs,
                        onPlay = { recording -> viewModel.playRecording(recording) },
                        onPause = { viewModel.pausePlayback() },
                        onSeek = { targetMs -> viewModel.seekPlayback(targetMs) },
                        onDelete = { recording -> viewModel.deleteRecording(recording) }
                    )
                }
            }
        }
    }

    // 1. Conversion Progress Modal
    conversionProgress?.let { progress ->
        ConversionProgressDialog(progress = progress)
    }

    // 2. Hardware Error Alert
    errorMessage?.let { errorText ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Hardware Alert") },
            text = { Text(errorText) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text("OK")
                }
            }
        )
    }

    // 3. Preset Selection Dialog
    if (showPresetDialog && !isRecording) {
        PresetSelectionDialog(
            currentPreset = selectedPreset,
            customPreset = customPreset,
            selectedDevice = selectedDevice,
            onPresetSelected = {
                viewModel.selectPreset(it)
                showPresetDialog = false
            },
            onSaveCustomPreset = { sr, ch, bd, fmt ->
                viewModel.updateCustomPreset(sr, ch, bd, fmt)
            },
            onDismiss = { showPresetDialog = false }
        )
    }

    // 4. Device Selector Dialog
    if (showDeviceDialog && !isRecording) {
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

    // 5. Save Take Dialog
    pendingSaveFile?.let { file ->
        SaveTakeDialog(
            tempFile = file,
            onSave = { name -> viewModel.confirmSaveTake(name) },
            onDiscard = { viewModel.discardTake() }
        )
    }

    // 6. Interrupted Take Recovery Dialog
    interruptedSession?.let { session ->
        RecoveryPromptDialog(
            session = session,
            onResume = { viewModel.resumeInterruptedSession() },
            onSave = { viewModel.finalizeInterruptedSession() },
            onDiscard = { viewModel.discardInterruptedSession() }
        )
    }
}

@Composable
private fun StudioContent(
    isRecording: Boolean,
    selectedDevice: AudioInputDevice?,
    selectedPreset: AudioPreset,
    onOpenDeviceSelector: () -> Unit,
    onOpenPresetSelector: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SectionPlaceholder(
            title = "1. Teleprompter Container (Collapsible / Mirror)",
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.8f)
        )

        SectionPlaceholder(
            title = "2. Waveform Visualizer",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.2f)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.8f)
                .padding(8.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                text = if (isRecording) "RECORDING ACTIVE" else "STANDBY",
                color = if (isRecording) Color(0xFFE53935) else Color.Gray,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Hardware & Preset Selectors (Disabled during active recording)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InputHardwarePill(
                    deviceName = selectedDevice?.name ?: "Detect Mic",
                    enabled = !isRecording,
                    onClick = onOpenDeviceSelector,
                    modifier = Modifier.weight(1f)
                )

                PresetPill(
                    preset = selectedPreset,
                    enabled = !isRecording,
                    onClick = onOpenPresetSelector,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ConversionProgressDialog(progress: Float) {
    AlertDialog(
        onDismissRequest = {}, // Modal dialog: prevent dismissing during transcoding
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Converting Audio...")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Exporting take to target preset format...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(16.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0xFF2C2C2E)
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun PresetPill(
    preset: AudioPreset,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alphaModifier = if (enabled) Modifier else Modifier.alpha(0.45f)

    Surface(
        modifier = modifier
            .then(alphaModifier)
            .clickable(enabled = enabled) { onClick() },
        color = Color(0xFF2C2C2E),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Color(0xFF3A3A3C) else Color(0xFF222224))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = if (enabled) Color(0xFF1E88E5) else Color.DarkGray,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "🎛 ${preset.name}",
                    color = if (enabled) Color.White else Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                Text(
                    text = "${preset.sampleRate / 1000}k • ${preset.displayBitDepth}",
                    color = Color.DarkGray,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun InputHardwarePill(
    deviceName: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alphaModifier = if (enabled) Modifier else Modifier.alpha(0.45f)

    Surface(
        modifier = modifier
            .then(alphaModifier)
            .clickable(enabled = enabled) { onClick() },
        color = Color(0xFF2C2C2E),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Color(0xFF3A3A3C) else Color(0xFF222224))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = if (enabled) Color(0xFFE53935) else Color.DarkGray,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "🎙 $deviceName",
                    color = if (enabled) Color.White else Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                Text(
                    text = if (enabled) "Hardware Input" else "Locked (Recording)",
                    color = Color.DarkGray,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun SaveTakeDialog(
    tempFile: File,
    onSave: (String) -> Unit,
    onDiscard: () -> Unit
) {
    val defaultTitle = remember {
        "Take_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
    }
    var titleText by remember { mutableStateOf(defaultTitle) }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Save Recording") },
        text = {
            Column {
                Text(text = "Enter a name for this take:", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    singleLine = true,
                    label = { Text("Take Title") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(titleText) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Save Take")
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text("Discard", color = Color(0xFFE53935))
            }
        }
    )
}

@Composable
private fun RecoveryPromptDialog(
    session: InterruptedSession,
    onResume: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Interrupted Take Found") },
        text = {
            Column {
                Text("An earlier session was unexpectedly terminated.")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Duration: ~${session.durationSeconds}s (${session.bytesWritten / 1024} KB)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("Would you like to resume recording from the end of this take, save it, or discard it?")
            }
        },
        confirmButton = {
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Resume Recording")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) {
                    Text("Discard", color = Color(0xFFE53935))
                }
                TextButton(onClick = onSave) {
                    Text("Save Take")
                }
            }
        }
    )
}

@Composable
fun PersistentDock(
    currentDestination: AppDestination,
    isRecording: Boolean,
    onLibraryClick: () -> Unit,
    onStudioClick: () -> Unit,
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
            TextButton(onClick = onLibraryClick) {
                Text(
                    text = "Library",
                    color = if (currentDestination == AppDestination.LIBRARY) Color(0xFF1E88E5) else Color.White
                )
            }

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

            TextButton(onClick = onStudioClick) {
                Text(
                    text = "Studio",
                    color = if (currentDestination == AppDestination.STUDIO) Color(0xFF1E88E5) else Color.White
                )
            }
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