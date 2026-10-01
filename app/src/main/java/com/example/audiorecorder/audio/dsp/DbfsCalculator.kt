package com.example.audiorecorder.audio.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

object DbfsCalculator {
    const val MIN_DBFS = -60.0f
    const val MAX_DBFS = 0.0f

    /**
     * Calculates peak amplitude in dBFS from a 32-bit float audio buffer [-1.0f, 1.0f].
     */
    fun calculatePeakDbfs(buffer: FloatArray, readCount: Int): Float {
        if (readCount <= 0) return MIN_DBFS
        var peak = 0.0f
        for (i in 0 until readCount) {
            val sample = abs(buffer[i])
            if (sample > peak) {
                peak = sample
            }
        }
        return linearToDbfs(peak)
    }

    /**
     * Calculates RMS (Root Mean Square) energy in dBFS.
     */
    fun calculateRmsDbfs(buffer: FloatArray, readCount: Int): Float {
        if (readCount <= 0) return MIN_DBFS
        var sumSquares = 0.0
        for (i in 0 until readCount) {
            val sample = buffer[i]
            sumSquares += (sample * sample)
        }
        val rms = sqrt(sumSquares / readCount).toFloat()
        return linearToDbfs(rms)
    }

    private fun linearToDbfs(linear: Float): Float {
        if (linear <= 0.0001f) return MIN_DBFS
        val db = 20.0f * log10(linear)
        return max(MIN_DBFS, db)
    }
}
