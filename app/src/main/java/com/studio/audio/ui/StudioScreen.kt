package com.studio.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun StudioScreen() {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { PersistentDock() }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Teleprompter Container
            SectionPlaceholder(
                title = "1. Teleprompter  (Collapsible / Mirror)",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.8f)
            )

            // 2. Waveform Visualizer & Zoom
            SectionPlaceholder(
                title = "2. Waveform Visualizer (40 Hz dynamic peaks, stationary playhead)",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.2f)
            )

            // 3. Control & Metrics Strip
            SectionPlaceholder(
                title = "3. Control & Metrics Strip (dBFS, 00:00:00.000, Pills)",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.7f)
            )
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

@Composable
fun PersistentDock() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
            TextButton(onClick = {}) {
                Text("Library", color = Color.White)
            }

            FloatingActionButton(
                onClick = {},
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(imageVector = Icons.Default.Mic, contentDescription = "Record")
            }

            TextButton(onClick = {}) {
                Text("Studio", color = Color.White)
            }
        }
    }
}
