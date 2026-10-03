package com.studio.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.studio.audio.core.audio.SavedRecording
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LibraryScreen(
    recordings: List<SavedRecording>,
    currentPlayingFile: File?,
    isPlaying: Boolean,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    onPlay: (SavedRecording) -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onDelete: (SavedRecording) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Recordings Library",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No saved takes yet.\nHit record in Studio to capture audio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(recordings, key = { it.file.absolutePath }) { recording ->
                    val isThisTrackActive = currentPlayingFile?.absolutePath == recording.file.absolutePath
                    RecordingItemRow(
                        recording = recording,
                        isCurrentTrack = isThisTrackActive,
                        isPlaying = isThisTrackActive && isPlaying,
                        currentPositionMs = if (isThisTrackActive) playbackPositionMs else 0L,
                        totalDurationMs = if (isThisTrackActive && playbackDurationMs > 0L) playbackDurationMs else recording.durationSeconds * 1000L,
                        onPlayPause = {
                            if (isThisTrackActive && isPlaying) {
                                onPause()
                            } else {
                                onPlay(recording)
                            }
                        },
                        onSeek = onSeek,
                        onDelete = { onDelete(recording) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingItemRow(
    recording: SavedRecording,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    val formattedDate = dateFormat.format(Date(recording.lastModified))
    val sizeMb = String.format(Locale.US, "%.1f MB", recording.sizeBytes / (1024f * 1024f))
    val channelLabel = if (recording.channelCount == 1) "Mono" else if (recording.channelCount == 2) "Stereo" else "${recording.channelCount}ch"
    val sampleRateLabel = if (recording.sampleRate % 1000 == 0) "${recording.sampleRate / 1000}kHz" else "${recording.sampleRate}Hz"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isCurrentTrack) Color(0xFF222831) else Color(0xFF1E1E1E),
        shape = RoundedCornerShape(12.dp),
        border = if (isCurrentTrack) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E88E5)) else null
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play / Pause Audition Button
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (isCurrentTrack) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2E),
                            RoundedCornerShape(8.dp)
                        )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recording.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$formattedDate • $sizeMb",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Audio Format & Spec Badge
                    Surface(
                        color = Color(0xFF2A2A2A),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "${recording.formatLabel} • $sampleRateLabel • $channelLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF81D4FA),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Take",
                        tint = Color.Gray
                    )
                }
            }

            // Seek slider and timestamp controls (active when selected/playing)
            if (isCurrentTrack) {
                Spacer(modifier = Modifier.height(8.dp))
                val progressFraction = if (totalDurationMs > 0L) {
                    (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Slider(
                    value = progressFraction,
                    onValueChange = { fraction ->
                        val targetMs = (fraction * totalDurationMs).toLong()
                        onSeek(targetMs)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color(0xFF3A3A3C)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatMsToTimestamp(currentPositionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.LightGray
                    )
                    Text(
                        text = formatMsToTimestamp(totalDurationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}


private fun formatMsToTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}