package com.studio.audio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.studio.audio.core.theme.StudioTheme
import com.studio.audio.ui.StudioScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme {
              val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { /* handle granted / denied */ }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                StudioScreen()
            }
        }
    }
}
