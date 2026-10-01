package com.example.audiorecorder.util

import java.util.Locale

object TimecodeFormatter {
    fun formatMillis(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000

        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
        } else {
            String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
        }
    }

    fun samplesToMillis(samples: Long, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        return (samples * 1000L) / sampleRate
    }
}
