package com.studio.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.studio.audio.core.theme.StudioTheme
import com.studio.audio.ui.StudioScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    // Permissions evaluated
                }

                LaunchedEffect(Unit) {
                    val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)

                    // Bluetooth runtime permission (Android 12+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
                    }

                    // Notification runtime permission (Android 13+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    permissionLauncher.launch(permissionsToRequest.toTypedArray())

                    // Optional: Request battery optimization exemption for uninterrupted long takes
                    requestBatteryExemptionIfNecessary()
                }

                StudioScreen()
            }
        }
    }

    private fun requestBatteryExemptionIfNecessary() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                // Some OEMs block direct intent; standard settings fallback
            }
        }
    }
}