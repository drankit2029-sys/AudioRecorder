package com.studio.audio.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioColorScheme = darkColorScheme(
    primary = Color(0xFFE53935),
    secondary = Color(0xFF1E88E5),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color(0xFFEEEEEE),
    onSurface = Color(0xFFEEEEEE)
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        content = content
    )
}
