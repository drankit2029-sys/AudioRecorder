package com.studio.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.studio.audio.core.audio.*

@Composable
fun PresetSelectionDialog(
    currentPreset: AudioPreset,
    customPreset: AudioPreset,
    selectedDevice: AudioInputDevice?,
    onPresetSelected: (AudioPreset) -> Unit,
    onSaveCustomPreset: (Int, Int, BitDepth?, AudioFormatType) -> Unit,
    onDismiss: () -> Unit
) {
    var showCustomEditor by remember { mutableStateOf(false) }

    if (showCustomEditor) {
        CustomPresetEditorDialog(
            initialPreset = customPreset,
            selectedDevice = selectedDevice,
            onSave = { sr, ch, bd, fmt ->
                onSaveCustomPreset(sr, ch, bd, fmt)
                showCustomEditor = false
            },
            onBack = { showCustomEditor = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Audio Presets")
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { showCustomEditor = true }) {
                    Icon(imageVector = Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Custom")
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(AudioPresetValidator.POPULAR_PRESETS) { preset ->
                    val validation = AudioPresetValidator.validate(preset, selectedDevice)
                    PresetCardItem(
                        preset = preset,
                        isSelected = currentPreset.id == preset.id,
                        validation = validation,
                        onSelect = { onPresetSelected(preset) }
                    )
                }

                item {
                    val customValidation = AudioPresetValidator.validate(customPreset, selectedDevice)
                    PresetCardItem(
                        preset = customPreset,
                        isSelected = currentPreset.id == customPreset.id,
                        validation = customValidation,
                        onSelect = { onPresetSelected(customPreset) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PresetCardItem(
    preset: AudioPreset,
    isSelected: Boolean,
    validation: CompatibilityResult,
    onSelect: () -> Unit
) {
    val isSupported = validation.isSupported
    val borderColor = when {
        isSelected -> Color(0xFF1E88E5)
        !isSupported -> Color(0xFF4A1A1A)
        else -> Color(0xFF2C2C2E)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isSupported) { onSelect() },
        color = if (isSupported) Color(0xFF1E1E1E) else Color(0xFF181414),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSupported) Color.White else Color.DarkGray
                    )
                    Text(
                        text = "${preset.sampleRate}Hz • ${if (preset.channelCount == 1) "Mono" else "Stereo"} • ${preset.displayBitDepth} • ${preset.format.extension.uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSupported) Color.LightGray else Color.DarkGray
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color(0xFF1E88E5),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = preset.description,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSupported) Color.Gray else Color(0xFF4E4E4E)
            )

            if (!isSupported) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D1212), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Incompatible with current microphone:",
                            color = Color(0xFFFF8A80),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    validation.unsupportedReasons.forEach { reason ->
                        Text(
                            text = "• $reason",
                            color = Color(0xFFFF5252),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomPresetEditorDialog(
    initialPreset: AudioPreset,
    selectedDevice: AudioInputDevice?,
    onSave: (Int, Int, BitDepth?, AudioFormatType) -> Unit,
    onBack: () -> Unit
) {
    var format by remember { mutableStateOf(initialPreset.format) }
    var bitDepth by remember { mutableStateOf(initialPreset.bitDepth) }
    var sampleRate by remember { mutableIntStateOf(initialPreset.sampleRate) }
    var channelCount by remember { mutableIntStateOf(initialPreset.channelCount) }

    // When the format changes, dynamically snap bit-depth to a valid state
    fun onFormatSelected(newFormat: AudioFormatType) {
        format = newFormat
        if (newFormat.supportedBitDepths.isEmpty()) {
            bitDepth = null
        } else if (bitDepth == null || bitDepth !in newFormat.supportedBitDepths) {
            bitDepth = newFormat.supportedBitDepths.first()
        }
        if (newFormat == AudioFormatType.OPUS && sampleRate !in listOf(8000, 12000, 16000, 24000, 48000)) {
            sampleRate = 48000
        }
    }

    val testPreset = remember(sampleRate, channelCount, bitDepth, format) {
        AudioPreset(
            id = "test_custom",
            name = "Custom Preset",
            description = "Configured format",
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitDepth = bitDepth,
            format = format,
            isCustom = true
        )
    }

    val validation = AudioPresetValidator.validate(testPreset, selectedDevice)

    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("Configure Custom Preset") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                // 1. Container / Codec Format
                item {
                    Text("Container / Codec", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    AudioFormatType.values().forEach { fmt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onFormatSelected(fmt) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = format == fmt, onClick = { onFormatSelected(fmt) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = fmt.label, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF2C2C2E))
                }

                // 2. Bit Depth (Strictly contextual to the selected format)
                item {
                    Text("Bit Depth", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Spacer(modifier = Modifier.height(6.dp))

                    if (format.supportedBitDepths.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF242426), RoundedCornerShape(6.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFF90CAF9),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Bit-depth is handled automatically by ${format.extension.uppercase()}'s perceptual encoder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            format.supportedBitDepths.forEach { depth ->
                                FilterChip(
                                    selected = bitDepth == depth,
                                    onClick = { bitDepth = depth },
                                    label = { Text(depth.label) }
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF2C2C2E))
                }

                // 3. Sampling Rate
                item {
                    Text("Sampling Rate", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val availableRates = if (format == AudioFormatType.OPUS) {
                            listOf(16000, 24000, 48000)
                        } else {
                            listOf(44100, 48000, 96000, 192000)
                        }
                        availableRates.forEach { rate ->
                            FilterChip(
                                selected = sampleRate == rate,
                                onClick = { sampleRate = rate },
                                label = { Text("${rate / 1000}k") }
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF2C2C2E))
                }

                // 4. Channels
                item {
                    Text("Channels", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AudioPresetValidator.AVAILABLE_CHANNEL_COUNTS.forEach { (count, _) ->
                            FilterChip(
                                selected = channelCount == count,
                                onClick = { channelCount = count },
                                label = { Text(if (count == 1) "Mono" else if (count == 2) "Stereo" else "${count}ch") }
                            )
                        }
                    }
                }

                // Real-time Error / Incompatibility Alerts
                if (!validation.isSupported) {
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2D1212), RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "⚠️ Invalid configuration or hardware mismatch:",
                                color = Color(0xFFFF8A80),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            validation.unsupportedReasons.forEach { reason ->
                                Text(
                                    text = "• $reason",
                                    color = Color(0xFFFF5252),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(sampleRate, channelCount, bitDepth, format) },
                enabled = validation.isSupported,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Apply Custom")
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text("Back") }
        }
    )
}