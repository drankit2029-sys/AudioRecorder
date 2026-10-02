package com.example.audiorecorder.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val filePath: String,
    val durationMs: Long,
    val sampleRate: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val format: String, // "WAV", "FLAC", "AAC", "MP3", "RAW_FLOAT"
    val fileSize: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val isTrashed: Boolean = false,
    val trashedDate: Long? = null
)
