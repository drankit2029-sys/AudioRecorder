package com.studio.audio.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    val context = LocalContext.current
    val activity = context as? Activity

    val destination by viewModel.currentDestination.collectAsState()
    val devices by viewModel.availableDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val selectedPreset by viewModel.selectedPreset.collectAsState()
    val customPreset by viewModel.customPreset.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val recordingTimeMs by viewModel.recordingTimeMs.collectAsState()
    val currentDbfs by viewModel.currentDbfs.collectAsState()

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

    var permissionsExplanationList by remember { mutableStateOf<List<String>?>(null) }
    var showSettingsRedirectDialog by remember { mutableStateOf(false) }

    fun checkMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val isBt = selectedDevice?.rawDeviceInfo?.let {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: false

        if (isBt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        return missing
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val stillMissing = checkMissingPermissions()
        if (stillMissing.isEmpty()) {
            permissionsExplanationList = null
            showSettingsRedirectDialog = false
            requestBatteryExemptionIfNecessary(context)
            viewModel.startRecordingTake()
        } else {
            val permanentlyDenied = activity?.let { act ->
                stillMissing.any { perm ->
                    !ActivityCompat.shouldShowRequestPermissionRationale(act, perm)
                }
            } ?: false

            if (permanentlyDenied) {
                showSettingsRedirectDialog = true
            }
            permissionsExplanationList = null
        }
    }

    fun handleRecordClick() {
        if (destination != AppDestination.STUDIO) {
            viewModel.navigateTo(AppDestination.STUDIO)
        }

        val missing = checkMissingPermissions()
        if (missing.isEmpty()) {
            requestBatteryExemptionIfNecessary(context)
            viewModel.startRecordingTake()
        } else {
            permissionsExplanationList = missing
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            PersistentDock(
                currentDestination = destination,
                isRecording = isRecording,
                isPaused = isPaused,
                onLibraryClick = { viewModel.navigateTo(AppDestination.LIBRARY) },
                onStudioClick = { viewModel.navigateTo(AppDestination.STUDIO) },
                onRecordClick = { handleRecordClick() },
                onPauseResumeClick = { viewModel.togglePauseResume() },
                onSaveClick = { viewModel.stopAndSaveRecording() }
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
                        isPaused = isPaused,
                        recordingTimeMs = recordingTimeMs,
                        currentDbfs = currentDbfs,
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

    // Explanatory Rationale Prompt
    permissionsExplanationList?.let { missingList ->
        PermissionExplanationDialog(
            missingPermissions = missingList,
            onConfirm = {
                val toRequest = missingList
                permissionsExplanationList = null
                permissionLauncher.launch(toRequest.toTypedArray())
            },
            onDismiss = {
                permissionsExplanationList = null
            }
        )
    }

    // Permanent Denial Settings Prompt
    if (showSettingsRedirectDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsRedirectDialog = false },
            title = { Text("Permission Required") },
            text = { Text("Required audio permissions were disabled with 'Don't ask again'. Please enable them in App Settings to record.") },
            confirmButton = {
                Button(onClick = {
                    showSettingsRedirectDialog = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsRedirectDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    conversionProgress?.let { progress ->
        ConversionProgressDialog(progress = progress)
    }

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

    if (showDeviceDialog && !isRecording) {
        DeviceSelectionDialog(
            devices = devices,
            currentDevice = selectedDevice,
            onDeviceSelected = { device ->
                viewModel.selectDevice(device)
                showDeviceDialog = false
            },
            onDismiss = { showDeviceDialog = false }
        )
    }

    pendingSaveFile?.let { file ->
        SaveTakeDialog(
            tempFile = file,
            onSave = { name -> viewModel.confirmSaveTake(name) },
            onDiscard = { viewModel.discardTake() }
        )
    }

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
private fun PermissionExplanationDialog(
    missingPermissions: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val micNeeded = missingPermissions.contains(Manifest.permission.RECORD_AUDIO)
    val notifNeeded = missingPermissions.contains(Manifest.permission.POST_NOTIFICATIONS)
    val btNeeded = missingPermissions.contains(Manifest.permission.BLUETOOTH_CONNECT)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Permissions Required",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Studio needs the following permissions to capture and safeguard your recording:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray
                )

                if (micNeeded) {
                    PermissionReasonRow(
                        title = "Microphone Access",
                        description = "Direct hardware access to capture uncompressed audio."
                    )
                }

                if (notifNeeded) {
                    PermissionReasonRow(
                        title = "Recording Notification",
                        description = "Maintains the background service so Android won't mute or kill long takes."
                    )
                }

                if (btNeeded) {
                    PermissionReasonRow(
                        title = "Bluetooth Connection",
                        description = "Enables communication with wireless headsets over the SCO audio link."
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Allow & Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now", color = Color.Gray)
            }
        }
    )
}

@Composable
private fun PermissionReasonRow(title: String, description: String) {
    Surface(
        color = Color(0xFF242426),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

private fun requestBatteryExemptionIfNecessary(context: Context) {
    try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Composable
private fun StudioContent(
    isRecording: Boolean,
    isPaused: Boolean,
    recordingTimeMs: Long,
    currentDbfs: Float,
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
                .weight(0.7f)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.3f)
                .padding(4.dp)
                .background(Color(0xFF141416), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF262628), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                StudioTimer(
                    durationMs = recordingTimeMs,
                    isRecording = isRecording,
                    isPaused = isPaused
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isRecording) {
                        if (isPaused) "PAUSED" else "32-BIT FLOAT STREAMING"
                    } else "READY TO CAPTURE",
                    color = if (isRecording) {
                        if (isPaused) Color(0xFFFFA000) else Color(0xFF81D4FA)
                    } else Color.Gray,
                    letterSpacing = 1.sp,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(8.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            DbfsMeter(
                dbfs = currentDbfs,
                isRecording = isRecording,
                isPaused = isPaused
            )

            Spacer(modifier = Modifier.height(14.dp))

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
private fun StudioTimer(
    durationMs: Long,
    isRecording: Boolean,
    isPaused: Boolean
) {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (durationMs % 1000) / 100

    val timeFormatted = String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .alpha(if (isRecording && !isPaused) alphaAnim else 1f)
                .background(
                    color = when {
                        !isRecording -> Color(0xFF424242)
                        isPaused -> Color(0xFFFFA000)
                        else -> Color(0xFFE53935)
                    },
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = timeFormatted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 36.sp,
            color = if (isRecording) Color.White else Color(0xFF757575)
        )
    }
}

@Composable
private fun DbfsMeter(
    dbfs: Float,
    isRecording: Boolean,
    isPaused: Boolean
) {
    val normalizedFraction = if (isRecording && !isPaused) {
        ((dbfs - (-60f)) / (0f - (-60f))).coerceIn(0f, 1f)
    } else 0f

    val animatedFraction by animateFloatAsState(
        targetValue = normalizedFraction,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "meterSmooth"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "dBFS METER",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                letterSpacing = 0.8.sp
            )
            Text(
                text = if (isRecording && !isPaused) String.format(Locale.US, "%.1f dBFS", dbfs) else "-∞ dBFS",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = if (dbfs > -3f && isRecording && !isPaused) Color(0xFFFF5252) else Color(0xFF81D4FA)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF141416))
                .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(6.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedFraction)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF2E7D32),
                                Color(0xFFFBC02D),
                                Color(0xFFE53935)
                            )
                        )
                    )
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("-60", "-36", "-24", "-12", "-6", "0").forEach { mark ->
                Text(
                    text = mark,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF5A5A5C)
                )
            }
        }
    }
}

@Composable
fun PersistentDock(
    currentDestination: AppDestination,
    isRecording: Boolean,
    isPaused: Boolean,
    onLibraryClick: () -> Unit,
    onStudioClick: () -> Unit,
    onRecordClick: () -> Unit,
    onPauseResumeClick: () -> Unit,
    onSaveClick: () -> Unit
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
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onLibraryClick) {
                Text(
                    text = "Library",
                    color = if (currentDestination == AppDestination.LIBRARY) Color(0xFF1E88E5) else Color.White
                )
            }

            if (!isRecording) {
                FloatingActionButton(
                    onClick = onRecordClick,
                    containerColor = Color(0xFFE53935),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Record"
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FloatingActionButton(
                        onClick = onPauseResumeClick,
                        containerColor = if (isPaused) Color(0xFF1E88E5) else Color(0xFFFFA000),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Resume Recording" else "Pause Recording",
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    FloatingActionButton(
                        onClick = onSaveClick,
                        containerColor = Color(0xFF43A047),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save Take",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
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
private fun ConversionProgressDialog(progress: Float) {
    AlertDialog(
        onDismissRequest = {},
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