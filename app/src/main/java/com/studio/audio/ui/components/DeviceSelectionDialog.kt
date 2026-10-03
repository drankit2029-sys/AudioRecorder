package com.studio.audio.ui.components

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studio.audio.core.audio.AudioInputDevice

@Composable
fun DeviceSelectionDialog(
    devices: List<AudioInputDevice>,
    currentDevice: AudioInputDevice?,
    onDeviceSelected: (AudioInputDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color(0xFFE53935),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Audio Input Hardware")
            }
        },
        text = {
            if (devices.isEmpty()) {
                Text(
                    text = "No hardware inputs detected.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(devices, key = { it.id }) { device ->
                        DeviceCardItem(
                            device = device,
                            isSelected = device.id == currentDevice?.id,
                            onSelect = { onDeviceSelected(device) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DeviceCardItem(
    device: AudioInputDevice,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFF1E88E5) else Color(0xFF2C2C2E)
    val containerColor = if (isSelected) Color(0xFF16222F) else Color(0xFF1C1C1E)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: Friendly Name, Type Badge & Selection Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = device.typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color(0xFF90CAF9) else Color(0xFF81D4FA)
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Active",
                        tint = Color(0xFF1E88E5),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Channels & Sample Rates
            val channelsText = if (device.channelCounts.isEmpty()) {
                "Arbitrary / System Managed"
            } else {
                device.channelCounts.sorted().joinToString { count ->
                    when (count) {
                        1 -> "1 (Mono)"
                        2 -> "2 (Stereo)"
                        else -> "$count ch"
                    }
                }
            }
            MetricRow(label = "Channels", value = channelsText)

            val ratesText = if (device.isUnconstrained || device.sampleRates.isEmpty()) {
                "Arbitrary / System Managed"
            } else {
                device.sampleRates.sorted().joinToString { "${it}Hz" }
            }
            MetricRow(label = "Sample Rates", value = ratesText)

            Spacer(modifier = Modifier.height(8.dp))

            // Raw Hardware HAL Diagnostics
            RawDeviceInfoBlock(device = device)
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = Color.LightGray,
            maxLines = 1
        )
    }
}

@Composable
private fun RawDeviceInfoBlock(device: AudioInputDevice) {
    val rawInfo = device.rawDeviceInfo

    Surface(
        color = Color(0xFF121214),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Color(0xFF262628)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.DeveloperBoard,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "RAW HAL DIAGNOSTICS",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (rawInfo != null) {
                RawPropertyRow(label = "Endpoint ID", value = "${rawInfo.id}")
                RawPropertyRow(label = "HAL Type", value = "${rawInfo.type} (${getHalTypeConstantName(rawInfo.type)})")
                RawPropertyRow(label = "Product Name", value = rawInfo.productName.toString().ifBlank { "N/A" })

                val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    rawInfo.address.ifBlank { "None (Internal Bus)" }
                } else {
                    "API < 28 (Unsupported)"
                }
                RawPropertyRow(label = "Port / Bus Address", value = address)
                RawPropertyRow(label = "Sink / Source", value = "isSource=${rawInfo.isSource}, isSink=${rawInfo.isSink}")
            } else {
                RawPropertyRow(label = "Endpoint ID", value = "${device.id}")
                RawPropertyRow(label = "Routing Mode", value = "Android Default / Calibrated Mic Array")
                RawPropertyRow(label = "HAL Binding", value = "AudioFlinger Master Input Stream")
            }
        }
    }
}

@Composable
private fun RawPropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color(0xFF757575)
        )
        Text(
            text = value,
            fontSize = 10.sp,
            color = Color(0xFFAAAAAA),
            maxLines = 1
        )
    }
}

private fun getHalTypeConstantName(type: Int): String {
    return when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "TYPE_BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "TYPE_USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "TYPE_LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "TYPE_LINE_DIGITAL"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TYPE_TELEPHONY"
        25 -> "TYPE_REMOTE_SUBMIX"
        28 -> "TYPE_ECHO_REFERENCE"
        else -> "TYPE_CODE_$type"
    }
}