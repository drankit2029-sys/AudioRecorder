#!/bin/sh
set -e

echo "==> 1. Creating Architecture Directory Tree..."
BASE="app/src/main/java/com/example/audiorecorder"
mkdir -p "$BASE/audio/engine"
mkdir -p "$BASE/audio/hardware"
mkdir -p "$BASE/audio/scratchpad"
mkdir -p "$BASE/audio/codec"
mkdir -p "$BASE/audio/dsp"
mkdir -p "$BASE/data/db"
mkdir -p "$BASE/data/model"
mkdir -p "$BASE/data/repository"
mkdir -p "$BASE/ui/library"
mkdir -p "$BASE/ui/studio"
mkdir -p "$BASE/ui/customviews"
mkdir -p "$BASE/ui/dialogs"
mkdir -p "$BASE/util"
mkdir -p "app/src/main/res/xml"

echo "==> 2. Updating Root build.gradle.kts with Kapt Plugin..."
cat << 'KOTLIN_ROOT' > build.gradle.kts
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
}
KOTLIN_ROOT

echo "==> 3. Updating app/build.gradle.kts with Dependencies & ViewBinding..."
cat << 'KOTLIN_APP' > app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.audiorecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.audiorecorder"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // MVVM & Coroutines
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}
KOTLIN_APP

echo "==> 4. Updating AndroidManifest.xml..."
cat << 'MANIFEST' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AudioRecorder">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

    </application>

</manifest>
MANIFEST

cat << 'PATHS' > app/src/main/res/xml/file_paths.xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="recordings" path="recordings/" />
</paths>
PATHS

echo "==> 5. Writing Core DSP & Utility Classes..."

# 5.1 TimecodeFormatter
cat << 'TIMECODE' > "$BASE/util/TimecodeFormatter.kt"
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
TIMECODE

# 5.2 DbfsCalculator
cat << 'DBFS' > "$BASE/audio/dsp/DbfsCalculator.kt"
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
DBFS

# 5.3 WavHeaderWriter
cat << 'WAV_HEADER' > "$BASE/audio/codec/WavHeaderWriter.kt"
package com.example.audiorecorder.audio.codec

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavHeaderWriter {
    const val FORMAT_PCM = 0x0001.toShort()
    const val FORMAT_IEEE_FLOAT = 0x0003.toShort()

    /**
     * Writes standard 44-byte RIFF header for PCM (16/24-bit) or IEEE Float (32-bit).
     */
    fun writeHeader(
        out: OutputStream,
        totalAudioBytes: Long,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int
    ) {
        val isFloat = bitDepth == 32
        val formatTag = if (isFloat) FORMAT_IEEE_FLOAT else FORMAT_PCM
        val byteRate = sampleRate * channels * (bitDepth / 8)
        val blockAlign = (channels * (bitDepth / 8)).toShort()
        val totalDataLen = totalAudioBytes + 36

        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)

            // "RIFF"
            put('R'.code.toByte()); put('I'.code.toByte()); put('F'.code.toByte()); put('F'.code.toByte())
            putInt(totalDataLen.toInt())
            // "WAVE"
            put('W'.code.toByte()); put('A'.code.toByte()); put('V'.code.toByte()); put('E'.code.toByte())
            // "fmt "
            put('f'.code.toByte()); put('m'.code.toByte()); put('t'.code.toByte()); put(' '.code.toByte())
            putInt(16) // Subchunk1Size for PCM
            putShort(formatTag)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitDepth.toShort())
            // "data"
            put('d'.code.toByte()); put('a'.code.toByte()); put('t'.code.toByte()); put('a'.code.toByte())
            putInt(totalAudioBytes.toInt())
        }

        out.write(header.array())
    }
}
WAV_HEADER

# 5.4 ScratchpadManager
cat << 'SCRATCHPAD' > "$BASE/audio/scratchpad/ScratchpadManager.kt"
package com.example.audiorecorder.audio.scratchpad

import com.example.audiorecorder.audio.codec.WavHeaderWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScratchpadManager(private val scratchFile: File) {

    private var randomAccessFile: RandomAccessFile? = null
    private var byteBuffer: ByteBuffer? = null

    fun openSession() {
        if (!scratchFile.parentFile.exists()) {
            scratchFile.parentFile.mkdirs()
        }
        randomAccessFile = RandomAccessFile(scratchFile, "rw")
    }

    /**
     * Appends 32-bit float samples directly to the file in little-endian format.
     */
    @Synchronized
    fun writeFloats(floats: FloatArray, count: Int) {
        val raf = randomAccessFile ?: return
        val requiredBytes = count * 4

        var bb = byteBuffer
        if (bb == null || bb.capacity() < requiredBytes) {
            bb = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer = bb
        } else {
            bb.clear()
        }

        for (i in 0 until count) {
            bb.putFloat(floats[i])
        }

        val array = ByteArray(requiredBytes)
        bb.position(0)
        bb.get(array)
        raf.write(array)
    }

    /**
     * Seeks to a specific sample index for Punch-and-Roll.
     */
    @Synchronized
    fun seekToSample(sampleIndex: Long, channels: Int) {
        val raf = randomAccessFile ?: return
        val byteOffset = sampleIndex * channels * 4L
        raf.seek(byteOffset)
    }

    /**
     * Truncates file at target sample index, discarding previous takes forward.
     */
    @Synchronized
    fun truncateAtSample(sampleIndex: Long, channels: Int) {
        val raf = randomAccessFile ?: return
        val byteOffset = sampleIndex * channels * 4L
        raf.setLength(byteOffset)
        raf.seek(byteOffset)
    }

    fun sync() {
        randomAccessFile?.fd?.sync()
    }

    fun getTotalAudioBytes(): Long {
        return randomAccessFile?.length() ?: 0L
    }

    fun close() {
        try {
            sync()
            randomAccessFile?.close()
        } catch (_: Exception) {}
        randomAccessFile = null
    }

    /**
     * Packages current scratchpad into a standalone 32-bit Float WAV file.
     */
    fun exportToFloatWav(destinationWav: File, sampleRate: Int, channels: Int) {
        close()
        val totalBytes = scratchFile.length()
        FileOutputStream(destinationWav).use { fos ->
            WavHeaderWriter.writeHeader(
                out = fos,
                totalAudioBytes = totalBytes,
                sampleRate = sampleRate,
                channels = channels,
                bitDepth = 32
            )
            FileInputStream(scratchFile).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                }
            }
        }
    }
}
SCRATCHPAD

echo "==> Setup of Step 1 completed successfully!"
