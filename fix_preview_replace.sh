#!/bin/sh
set -e

BASE="app/src/main/java/com/example/audiorecorder"
RES="app/src/main/res"

echo "==> 1. Updating ScratchpadManager.kt (persisting file pointers & safe sessions)..."
cat << 'SCRATCHPAD' > "$BASE/audio/scratchpad/ScratchpadManager.kt"
package com.example.audiorecorder.audio.scratchpad

import com.example.audiorecorder.audio.codec.WavHeaderWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class ScratchpadManager(val scratchFile: File) {

    private val tailCacheFile = File(scratchFile.parentFile ?: File("."), "tail_cache.raw")
    private var randomAccessFile: RandomAccessFile? = null
    private var byteBuffer: ByteBuffer? = null
    var isTailShelved = false
        private set

    @Synchronized
    fun openSession() {
        scratchFile.parentFile?.mkdirs()
        if (randomAccessFile == null) {
            randomAccessFile = RandomAccessFile(scratchFile, "rw")
        }
    }

    /**
     * In-place seek to a sample without resetting file structure.
     */
    @Synchronized
    fun seekToSample(sampleIndex: Long, channels: Int) {
        openSession()
        val raf = randomAccessFile ?: return
        val byteOffset = sampleIndex * channels * 4L
        if (byteOffset <= raf.length()) {
            raf.seek(byteOffset)
        }
    }

    /**
     * Shelves downstream audio from [punchOutSample] to EOF into tail cache.
     * Truncates file at [punchInSample] so new incoming audio streams continuously.
     */
    @Synchronized
    fun prepareRangeReplacement(punchInSample: Long, punchOutSample: Long, channels: Int) {
        openSession()
        val raf = randomAccessFile ?: return
        val punchInByte = punchInSample * channels * 4L
        val punchOutByte = punchOutSample * channels * 4L
        val totalBytes = raf.length()

        if (punchOutByte < totalBytes) {
            raf.seek(punchOutByte)
            FileOutputStream(tailCacheFile).use { fos ->
                val buffer = ByteArray(64 * 1024)
                var bytesRemaining = totalBytes - punchOutByte
                while (bytesRemaining > 0) {
                    val read = raf.read(buffer, 0, min(buffer.size.toLong(), bytesRemaining).toInt())
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    bytesRemaining -= read
                }
            }
            isTailShelved = true
        } else {
            isTailShelved = false
        }

        raf.setLength(punchInByte)
        raf.seek(punchInByte)
    }

    @Synchronized
    fun writeFloats(floats: FloatArray, count: Int) {
        val raf = randomAccessFile ?: return
        val requiredBytes = count * 4

        val currentBuf = byteBuffer
        val targetBuffer: ByteBuffer = if (currentBuf == null || currentBuf.capacity() < requiredBytes) {
            val newBuf = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer = newBuf
            newBuf
        } else {
            currentBuf.clear()
            currentBuf
        }

        for (i in 0 until count) {
            targetBuffer.putFloat(floats[i])
        }

        val array = ByteArray(requiredBytes)
        targetBuffer.position(0)
        targetBuffer.get(array)
        raf.write(array)
    }

    @Synchronized
    fun spliceTailBack(sampleRate: Int, channels: Int, crossfadeMs: Int = 5) {
        if (!isTailShelved || !tailCacheFile.exists() || tailCacheFile.length() == 0L) {
            isTailShelved = false
            return
        }

        val raf = randomAccessFile ?: return
        val crossfadeSamples = (sampleRate * crossfadeMs) / 1000
        val crossfadeBytes = crossfadeSamples * channels * 4

        FileInputStream(tailCacheFile).use { tailStream ->
            if (raf.length() >= crossfadeBytes && tailCacheFile.length() >= crossfadeBytes) {
                applyBoundaryCrossfade(raf, tailStream, crossfadeSamples, channels)
            }

            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (tailStream.read(buffer).also { read = it } != -1) {
                raf.write(buffer, 0, read)
            }
        }

        tailCacheFile.delete()
        isTailShelved = false
    }

    private fun applyBoundaryCrossfade(
        targetRaf: RandomAccessFile,
        tailStream: FileInputStream,
        crossfadeSamples: Int,
        channels: Int
    ) {
        val totalFloats = crossfadeSamples * channels
        val byteCount = totalFloats * 4

        val targetStartOffset = targetRaf.length() - byteCount
        targetRaf.seek(targetStartOffset)
        val endBytes = ByteArray(byteCount)
        targetRaf.readFully(endBytes)

        val tailBytes = ByteArray(byteCount)
        var totalRead = 0
        while (totalRead < byteCount) {
            val r = tailStream.read(tailBytes, totalRead, byteCount - totalRead)
            if (r == -1) break
            totalRead += r
        }

        val endBuf = ByteBuffer.wrap(endBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val tailBuf = ByteBuffer.wrap(tailBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

        val blendedArray = ByteArray(byteCount)
        val blendedBuf = ByteBuffer.wrap(blendedArray).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until crossfadeSamples) {
            val alpha = i.toFloat() / crossfadeSamples.toFloat()
            for (ch in 0 until channels) {
                val sampleEnd = endBuf.get()
                val sampleTail = tailBuf.get()
                val blended = (sampleEnd * (1.0f - alpha)) + (sampleTail * alpha)
                blendedBuf.putFloat(blended)
            }
        }

        targetRaf.seek(targetStartOffset)
        targetRaf.write(blendedArray)
    }

    fun sync() {
        randomAccessFile?.fd?.sync()
    }

    fun getTotalAudioBytes(): Long {
        return randomAccessFile?.length() ?: scratchFile.length()
    }

    fun close() {
        try {
            sync()
            randomAccessFile?.close()
        } catch (_: Exception) {}
        randomAccessFile = null
    }

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
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                }
            }
        }
    }
}
SCRATCHPAD

echo "==> 2. Updating AudioPlaybackEngine.kt (mutable listener & WAV header offset)..."
cat << 'PLAYBACK' > "$BASE/audio/engine/AudioPlaybackEngine.kt"
package com.example.audiorecorder.audio.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

interface AudioPlaybackListener {
    fun onPlaybackTick(currentSample: Long, currentMs: Long)
    fun onPlaybackFinished()
    fun onPreRollCountdown(secondsRemaining: Int)
    fun onPreRollFinished()
    fun onError(errorMessage: String)
}

class AudioPlaybackEngine(
    private val context: Context,
    var listener: AudioPlaybackListener? = null
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isPreRollActive = AtomicBoolean(false)

    fun isHeadsetConnected(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
    }

    fun startPlayback(
        scratchFile: File,
        startSample: Long,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        if (isPlaying.get()) stopPlayback()
        if (!scratchFile.exists() || scratchFile.length() == 0L) {
            listener?.onError("Playback file does not exist or is empty.")
            return false
        }

        isPreRollActive.set(false)
        return initializeAndStart(scratchFile, startSample, endSample = null, sampleRate, channels)
    }

    private fun initializeAndStart(
        scratchFile: File,
        startSample: Long,
        endSample: Long?,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        val channelConfig = if (channels == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        if (minBufferSize <= 0) {
            listener?.onError("Invalid hardware playback buffer configuration.")
            return false
        }

        val bufferSize = minBufferSize * 2

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                listener?.onError("Failed to initialize AudioTrack.")
                track.release()
                return false
            }

            audioTrack = track
            track.play()
            isPlaying.set(true)
            isPaused.set(false)

            playbackThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                playbackLoop(scratchFile, startSample, endSample, sampleRate, channels, bufferSize / 4)
            }, "AudioPlaybackThread").apply {
                start()
            }

            return true
        } catch (e: Exception) {
            listener?.onError("Playback initialization failed: ${e.message}")
            release()
            return false
        }
    }

    private fun playbackLoop(
        scratchFile: File,
        startSample: Long,
        endSample: Long?,
        sampleRate: Int,
        channels: Int,
        floatChunkSize: Int
    ) {
        val track = audioTrack ?: return
        val byteChunkSize = floatChunkSize * 4
        val rawBuffer = ByteArray(byteChunkSize)
        val floatBuffer = FloatArray(floatChunkSize)
        val byteBuf = ByteBuffer.wrap(rawBuffer).order(ByteOrder.LITTLE_ENDIAN)

        // Account for RIFF 44-byte header if playing back a finalized WAV file
        val isWav = scratchFile.name.endsWith(".wav", ignoreCase = true)
        val headerOffset = if (isWav) 44L else 0L

        var currentSample = startSample
        val startByteOffset = headerOffset + (startSample * channels * 4L)
        var lastSecondsLeft = -1

        try {
            RandomAccessFile(scratchFile, "r").use { raf ->
                val totalLength = raf.length()
                if (startByteOffset >= totalLength) {
                    notifyFinish()
                    return
                }

                raf.seek(startByteOffset)

                while (isPlaying.get()) {
                    if (isPaused.get()) {
                        try {
                            Thread.sleep(20)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }

                    if (endSample != null && currentSample >= endSample) {
                        break
                    }

                    val samplesToRead = if (endSample != null) {
                        min(floatChunkSize.toLong(), (endSample - currentSample) * channels).toInt()
                    } else {
                        floatChunkSize
                    }

                    val bytesToRead = samplesToRead * 4
                    val bytesRead = raf.read(rawBuffer, 0, bytesToRead)
                    if (bytesRead <= 0) break

                    val actualFloatsRead = bytesRead / 4
                    byteBuf.position(0)
                    for (i in 0 until actualFloatsRead) {
                        floatBuffer[i] = byteBuf.getFloat()
                    }

                    track.write(floatBuffer, 0, actualFloatsRead, AudioTrack.WRITE_BLOCKING)

                    val framesAdvanced = actualFloatsRead / channels
                    currentSample += framesAdvanced

                    val currentMs = (currentSample * 1000L) / sampleRate
                    listener?.onPlaybackTick(currentSample, currentMs)

                    if (isPreRollActive.get() && endSample != null) {
                        val samplesRemaining = max(0L, endSample - currentSample)
                        val secondsLeft = ((samplesRemaining.toDouble() / sampleRate) + 0.99).toInt()
                        if (secondsLeft != lastSecondsLeft && secondsLeft > 0) {
                            lastSecondsLeft = secondsLeft
                            listener?.onPreRollCountdown(secondsLeft)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            listener?.onError("Error during playback: ${e.message}")
        } finally {
            notifyFinish()
        }
    }

    private fun notifyFinish() {
        val wasPreRoll = isPreRollActive.getAndSet(false)
        stopPlayback()

        if (wasPreRoll) {
            listener?.onPreRollFinished()
        } else {
            listener?.onPlaybackFinished()
        }
    }

    fun pausePlayback() {
        isPaused.set(true)
        try { audioTrack?.pause() } catch (_: Exception) {}
    }

    fun resumePlayback() {
        isPaused.set(false)
        try { audioTrack?.play() } catch (_: Exception) {}
    }

    fun stopPlayback() {
        isPlaying.set(false)
        isPaused.set(false)

        try {
            playbackThread?.interrupt()
            playbackThread?.join(300)
        } catch (_: Exception) {}
        playbackThread = null

        try {
            audioTrack?.stop()
            audioTrack?.flush()
        } catch (_: Exception) {}

        release()
    }

    fun release() {
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    fun isPlaying(): Boolean = isPlaying.get()
    fun isPaused(): Boolean = isPaused.get()
}
PLAYBACK

echo "==> 3. Updating StudioViewModel.kt (scrub tracking & tail peak shelving)..."
cat << 'STUDIO_VM' > "$BASE/ui/studio/StudioViewModel.kt"
package com.example.audiorecorder.ui.studio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.audiorecorder.audio.hardware.AudioPreset
import com.example.audiorecorder.audio.hardware.DiscoveredMic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

enum class StudioState { IDLE, RECORDING, PAUSED, PREVIEWING }
enum class PunchMode { PREVIEW, REPLACE }

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val _studioState = MutableStateFlow(StudioState.IDLE)
    val studioState: StateFlow<StudioState> = _studioState.asStateFlow()

    private val _punchMode = MutableStateFlow(PunchMode.PREVIEW)
    val punchMode: StateFlow<PunchMode> = _punchMode.asStateFlow()

    private val _selectedMic = MutableStateFlow<DiscoveredMic?>(null)
    val selectedMic: StateFlow<DiscoveredMic?> = _selectedMic.asStateFlow()

    private val _selectedPreset = MutableStateFlow(AudioPreset.STANDARD_PODCAST)
    val selectedPreset: StateFlow<AudioPreset> = _selectedPreset.asStateFlow()

    private val _peakDbfs = MutableStateFlow(-60.0f)
    val peakDbfs: StateFlow<Float> = _peakDbfs.asStateFlow()

    private val _rmsDbfs = MutableStateFlow(-60.0f)
    val rmsDbfs: StateFlow<Float> = _rmsDbfs.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    // Waveform peak stream & history
    private val _waveformPeaks = MutableStateFlow<List<Float>>(emptyList())
    val waveformPeaks: StateFlow<List<Float>> = _waveformPeaks.asStateFlow()

    private val _newPeakEvent = MutableSharedFlow<Float>(extraBufferCapacity = 128)
    val newPeakEvent: SharedFlow<Float> = _newPeakEvent.asSharedFlow()

    // Shelved peaks for punch-and-roll tail preservation
    private var shelvedTailPeaks: List<Float> = emptyList()

    // Scrub positions
    var punchInSampleIndex = 0L
        private set
    var punchInPeakIndex = 0
        private set

    // Teleprompter state
    private val _isPrompterVisible = MutableStateFlow(true)
    val isPrompterVisible: StateFlow<Boolean> = _isPrompterVisible.asStateFlow()

    private val _prompterScript = MutableStateFlow("Welcome to Audio Studio. Tap 'Script' to edit or import a file.")
    val prompterScript: StateFlow<String> = _prompterScript.asStateFlow()

    private val _isPrompterAutoScrolling = MutableStateFlow(false)
    val isPrompterAutoScrolling: StateFlow<Boolean> = _isPrompterAutoScrolling.asStateFlow()

    private val _wordsPerLine = MutableStateFlow(8)
    val wordsPerLine: StateFlow<Int> = _wordsPerLine.asStateFlow()

    private val _scrollSpeed = MutableStateFlow(1.5f)
    val scrollSpeed: StateFlow<Float> = _scrollSpeed.asStateFlow()

    private val _fontSizeSp = MutableStateFlow(22f)
    val fontSizeSp: StateFlow<Float> = _fontSizeSp.asStateFlow()

    fun setStudioState(state: StudioState) { _studioState.value = state }
    fun setPunchMode(mode: PunchMode) { _punchMode.value = mode }
    fun togglePunchMode() {
        _punchMode.value = if (_punchMode.value == PunchMode.PREVIEW) PunchMode.REPLACE else PunchMode.PREVIEW
    }

    fun setSelectedMic(mic: DiscoveredMic?) { _selectedMic.value = mic }
    fun setSelectedPreset(preset: AudioPreset) { _selectedPreset.value = preset }

    fun updateDbfs(peak: Float, rms: Float) {
        _peakDbfs.value = peak
        _rmsDbfs.value = rms
    }

    fun setScrubPosition(sampleIndex: Long, peakIndex: Int) {
        punchInSampleIndex = sampleIndex
        punchInPeakIndex = peakIndex
    }

    fun preparePunchWaveform(peakIndex: Int) {
        val current = _waveformPeaks.value
        if (peakIndex < current.size) {
            shelvedTailPeaks = current.subList(peakIndex, current.size).toList()
            _waveformPeaks.value = current.subList(0, peakIndex).toList()
        } else {
            shelvedTailPeaks = emptyList()
        }
    }

    fun spliceTailPeaksBack() {
        if (shelvedTailPeaks.isNotEmpty()) {
            val combined = ArrayList(_waveformPeaks.value)
            combined.addAll(shelvedTailPeaks)
            _waveformPeaks.value = combined
            shelvedTailPeaks = emptyList()
        }
    }

    fun addLivePeak(peak: Float) {
        val current = _waveformPeaks.value.toMutableList()
        current.add(peak)
        _waveformPeaks.value = current
        _newPeakEvent.tryEmit(peak)
    }

    fun setWaveformPeaks(peaks: List<Float>) {
        _waveformPeaks.value = peaks
        punchInPeakIndex = peaks.size
        punchInSampleIndex = (peaks.size.toLong() * 25L * _selectedPreset.value.sampleRate) / 1000L
    }

    fun clearWaveform() {
        _waveformPeaks.value = emptyList()
        shelvedTailPeaks = emptyList()
        punchInPeakIndex = 0
        punchInSampleIndex = 0L
    }

    fun setElapsedMillis(ms: Long) { _elapsedMillis.value = ms }

    fun togglePrompterVisibility() { _isPrompterVisible.value = !_isPrompterVisible.value }
    fun setPrompterScript(text: String) { _prompterScript.value = text }
    fun togglePrompterAutoScroll() { _isPrompterAutoScrolling.value = !_isPrompterAutoScrolling.value }

    fun adjustWordsPerLine(delta: Int) {
        _wordsPerLine.value = max(3, min(25, _wordsPerLine.value + delta))
    }

    fun adjustScrollSpeed(delta: Float) {
        _scrollSpeed.value = max(0.5f, min(10.0f, _scrollSpeed.value + delta))
    }

    fun adjustFontSize(delta: Float) {
        _fontSizeSp.value = max(12f, min(48f, _fontSizeSp.value + delta))
    }
}
STUDIO_VM

echo "==> 4. Updating fragment_studio.xml (adding dedicated Audition Button in timecode strip)..."
cat << 'FRAG_STUDIO' > "$RES/layout/fragment_studio.xml"
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#101214">

    <!-- 1. Teleprompter Section -->
    <LinearLayout
        android:id="@+id/layoutPrompterContainer"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:orientation="vertical"
        android:background="#16181B">

        <!-- Prompter Header Controls -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="40dp"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingHorizontal="12dp"
            android:background="#1E2124">

            <Button
                android:id="@+id/btnScript"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Script ▼"
                android:textColor="#448AFF"
                android:textSize="12sp" />

            <View
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <Button
                android:id="@+id/btnScrollToggle"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Auto-Scroll"
                android:textColor="#00E676"
                android:textSize="12sp" />

            <Button
                android:id="@+id/btnMirror"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Mirror ⮂"
                android:textColor="#FFD600"
                android:textSize="12sp" />
        </LinearLayout>

        <!-- Prompter Viewport -->
        <com.example.audiorecorder.ui.customviews.TeleprompterView
            android:id="@+id/teleprompterView"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:padding="16dp" />

        <!-- Prompter Tuning Steppers -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="36dp"
            android:orientation="horizontal"
            android:gravity="center"
            android:background="#181A1D"
            android:paddingHorizontal="8dp">

            <TextView
                android:id="@+id/tvWordsDec"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="-"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <TextView
                android:id="@+id/tvWordsValue"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="8 w/l"
                android:textColor="#9E9E9E"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/tvWordsInc"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="+"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <View
                android:layout_width="16dp"
                android:layout_height="1dp" />

            <TextView
                android:id="@+id/tvSpeedDec"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="-"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <TextView
                android:id="@+id/tvSpeedValue"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Speed"
                android:textColor="#9E9E9E"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/tvSpeedInc"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="+"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <View
                android:layout_width="16dp"
                android:layout_height="1dp" />

            <TextView
                android:id="@+id/tvFontDec"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="-"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <TextView
                android:id="@+id/tvFontValue"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Size"
                android:textColor="#9E9E9E"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/tvFontInc"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="+"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />
        </LinearLayout>
    </LinearLayout>

    <!-- 2. Waveform Visualizer & Zoom Section -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="160dp"
        android:orientation="horizontal"
        android:background="#121416"
        android:padding="8dp">

        <com.example.audiorecorder.ui.customviews.WaveformVisualizerView
            android:id="@+id/waveformVisualizerView"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1" />

        <LinearLayout
            android:layout_width="36dp"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:gravity="center">

            <Button
                android:id="@+id/btnZoomIn"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="36dp"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:text="+"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <Button
                android:id="@+id/btnZoomOut"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="36dp"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:text="-"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />
        </LinearLayout>
    </LinearLayout>

    <!-- 3. Status & Control Strip -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingHorizontal="12dp"
        android:paddingTop="8dp"
        android:paddingBottom="12dp"
        android:background="#181A1D">

        <!-- dBFS Meter, Timecode, and In-Studio Audition Button -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="48dp"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <com.example.audiorecorder.ui.customviews.DbfsMeterView
                android:id="@+id/dbfsMeterView"
                android:layout_width="14dp"
                android:layout_height="match_parent"
                android:layout_marginEnd="12dp" />

            <TextView
                android:id="@+id/tvTimecode"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="00:00:00.000"
                android:textColor="#FFFFFF"
                android:textSize="26sp"
                android:textStyle="bold"
                android:fontFamily="monospace" />

            <!-- Direct In-Studio Audition Trigger -->
            <ImageButton
                android:id="@+id/btnStudioPlayPause"
                android:layout_width="44dp"
                android:layout_height="44dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@android:drawable/ic_media_play"
                app:tint="#00E5FF"
                android:contentDescription="Audition preview from playhead" />
        </LinearLayout>

        <!-- Action Pills Row -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="8dp">

            <Button
                android:id="@+id/btnMicSelector"
                style="@style/Widget.Material3.Button.TonalButton"
                android:layout_width="0dp"
                android:layout_height="36dp"
                android:layout_weight="1"
                android:layout_marginEnd="4dp"
                android:padding="0dp"
                android:text="🎙 Built-in"
                android:textSize="11sp" />

            <Button
                android:id="@+id/btnPresetSelector"
                style="@style/Widget.Material3.Button.TonalButton"
                android:layout_width="0dp"
                android:layout_height="36dp"
                android:layout_weight="1"
                android:layout_marginEnd="4dp"
                android:padding="0dp"
                android:text="🎛 48kHz"
                android:textSize="11sp" />

            <Button
                android:id="@+id/btnPrompterToggle"
                style="@style/Widget.Material3.Button.TonalButton"
                android:layout_width="0dp"
                android:layout_height="36dp"
                android:layout_weight="1"
                android:layout_marginEnd="4dp"
                android:padding="0dp"
                android:text="🗎 Prompter"
                android:textSize="11sp" />

            <Button
                android:id="@+id/btnModeToggle"
                style="@style/Widget.Material3.Button.TonalButton"
                android:layout_width="0dp"
                android:layout_height="36dp"
                android:layout_weight="1"
                android:padding="0dp"
                android:text="▶ Preview"
                android:textColor="#00E5FF"
                android:textSize="11sp" />
        </LinearLayout>
    </LinearLayout>

</LinearLayout>
FRAG_STUDIO

echo "==> 5. Updating StudioFragment.kt (linking mode toggles and audition events)..."
cat << 'STUDIO_FRAG' > "$BASE/ui/studio/StudioFragment.kt"
package com.example.audiorecorder.ui.studio

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.audiorecorder.MainActivity
import com.example.audiorecorder.audio.hardware.MicrophoneManager
import com.example.audiorecorder.databinding.FragmentStudioBinding
import com.example.audiorecorder.ui.customviews.WaveformScrubListener
import com.example.audiorecorder.util.TimecodeFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StudioFragment : Fragment(), WaveformScrubListener {

    private var _binding: FragmentStudioBinding? = null
    val binding get() = _binding!!
    private val viewModel: StudioViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.waveformVisualizerView.scrubListener = this
        binding.waveformVisualizerView.setPeaks(viewModel.waveformPeaks.value)

        setupPrompterControls()
        setupActionPills()
        setupZoomControls()
        setupAuditionButton()
        observeStudioState()
    }

    private fun setupAuditionButton() {
        binding.btnStudioPlayPause.setOnClickListener {
            (activity as? MainActivity)?.toggleStudioPreview()
        }
    }

    private fun setupPrompterControls() {
        binding.btnScript.setOnClickListener { showScriptEditDialog() }
        binding.btnScrollToggle.setOnClickListener {
            viewModel.togglePrompterAutoScroll()
            if (viewModel.isPrompterAutoScrolling.value) {
                binding.teleprompterView.startAutoScroll()
                binding.btnScrollToggle.setTextColor(0xFF00E676.toInt())
            } else {
                binding.teleprompterView.pauseAutoScroll()
                binding.btnScrollToggle.setTextColor(0xFFFFFFFF.toInt())
            }
        }
        binding.btnMirror.setOnClickListener { binding.teleprompterView.toggleMirror() }

        binding.tvWordsInc.setOnClickListener { viewModel.adjustWordsPerLine(1) }
        binding.tvWordsDec.setOnClickListener { viewModel.adjustWordsPerLine(-1) }
        binding.tvSpeedInc.setOnClickListener {
            viewModel.adjustScrollSpeed(0.5f)
            binding.teleprompterView.setScrollSpeed(viewModel.scrollSpeed.value)
        }
        binding.tvSpeedDec.setOnClickListener {
            viewModel.adjustScrollSpeed(-0.5f)
            binding.teleprompterView.setScrollSpeed(viewModel.scrollSpeed.value)
        }
        binding.tvFontInc.setOnClickListener {
            viewModel.adjustFontSize(2f)
            binding.teleprompterView.setFontSize(viewModel.fontSizeSp.value)
        }
        binding.tvFontDec.setOnClickListener {
            viewModel.adjustFontSize(-2f)
            binding.teleprompterView.setFontSize(viewModel.fontSizeSp.value)
        }
    }

    private fun setupActionPills() {
        binding.btnMicSelector.setOnClickListener { showMicrophonePicker() }
        binding.btnPresetSelector.setOnClickListener { }
        binding.btnPrompterToggle.setOnClickListener {
            viewModel.togglePrompterVisibility()
            binding.layoutPrompterContainer.visibility =
                if (viewModel.isPrompterVisible.value) View.VISIBLE else View.GONE
        }
        binding.btnModeToggle.setOnClickListener {
            viewModel.togglePunchMode()
        }
    }

    private fun setupZoomControls() {
        binding.btnZoomIn.setOnClickListener { binding.waveformVisualizerView.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.waveformVisualizerView.zoomOut() }
    }

    private fun observeStudioState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.punchMode.collectLatest { mode ->
                        if (mode == PunchMode.PREVIEW) {
                            binding.btnModeToggle.text = "▶ Preview"
                            binding.btnModeToggle.setTextColor(0xFF00E5FF.toInt())
                        } else {
                            binding.btnModeToggle.text = "⎌ Replace"
                            binding.btnModeToggle.setTextColor(0xFFFF3D71.toInt())
                        }
                    }
                }
                launch {
                    viewModel.studioState.collectLatest { state ->
                        if (state == StudioState.PREVIEWING) {
                            binding.btnStudioPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                        } else {
                            binding.btnStudioPlayPause.setImageResource(android.R.drawable.ic_media_play)
                        }
                    }
                }
                launch {
                    viewModel.elapsedMillis.collectLatest { ms ->
                        binding.tvTimecode.text = TimecodeFormatter.formatMillis(ms)
                        if (viewModel.studioState.value != StudioState.RECORDING) {
                            val targetIndex = (ms / 25L).toInt()
                            binding.waveformVisualizerView.setPlayheadIndex(targetIndex)
                        }
                    }
                }
                launch {
                    viewModel.newPeakEvent.collect { peak ->
                        binding.waveformVisualizerView.addLivePeak(peak)
                    }
                }
                launch {
                    viewModel.peakDbfs.collectLatest { peak ->
                        binding.dbfsMeterView.setLevels(viewModel.rmsDbfs.value, peak)
                    }
                }
                launch {
                    viewModel.waveformPeaks.collectLatest { peaks ->
                        if (viewModel.studioState.value != StudioState.RECORDING) {
                            binding.waveformVisualizerView.setPeaks(peaks)
                        }
                    }
                }
                launch {
                    viewModel.wordsPerLine.collectLatest { words ->
                        binding.tvWordsValue.text = "$words w/l"
                    }
                }
                launch {
                    viewModel.prompterScript.collectLatest { script ->
                        binding.teleprompterView.setScript(script)
                    }
                }
            }
        }
    }

    private fun showScriptEditDialog() {
        val input = EditText(requireContext()).apply {
            setText(viewModel.prompterScript.value)
            setLines(6)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Edit Prompter Script")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                viewModel.setPrompterScript(input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMicrophonePicker() {
        val micMgr = MicrophoneManager(requireContext())
        val mics = micMgr.enumerateMicrophones()
        val names = mics.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Select Audio Input")
            .setItems(names) { _, which ->
                val chosen = mics[which]
                viewModel.setSelectedMic(chosen)
                binding.btnMicSelector.text = "🎙 ${chosen.typeName}"
            }
            .show()
    }

    override fun onScrubStart() {
        (activity as? MainActivity)?.pauseActiveAudioForScrub()
    }

    override fun onScrubbing(peakIndex: Int) {
        val ms = peakIndex.toLong() * 25L
        viewModel.setElapsedMillis(ms)
    }

    override fun onScrubStop(finalPeakIndex: Int) {
        val preset = viewModel.selectedPreset.value
        val sampleOffset = (finalPeakIndex.toLong() * 25L * preset.sampleRate) / 1000L
        viewModel.setScrubPosition(sampleOffset, finalPeakIndex)
        (activity as? MainActivity)?.seekScratchpadToSample(sampleOffset, preset.channels)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
STUDIO_FRAG

echo "==> 6. Updating MainActivity.kt (full Punch-and-Roll & Audition Preview state loop)..."
cat << 'MAIN_ACT' > "$BASE/MainActivity.kt"
package com.example.audiorecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.audiorecorder.audio.engine.AudioCaptureListener
import com.example.audiorecorder.audio.engine.AudioPlaybackListener
import com.example.audiorecorder.audio.engine.AudioRecordingService
import com.example.audiorecorder.data.db.RecordingEntity
import com.example.audiorecorder.data.repository.RecordingRepository
import com.example.audiorecorder.databinding.ActivityMainBinding
import com.example.audiorecorder.ui.library.LibraryFragment
import com.example.audiorecorder.ui.studio.PunchMode
import com.example.audiorecorder.ui.studio.StudioFragment
import com.example.audiorecorder.ui.studio.StudioState
import com.example.audiorecorder.ui.studio.StudioViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity(), AudioCaptureListener, AudioPlaybackListener {

    private lateinit var binding: ActivityMainBinding
    private val studioViewModel: StudioViewModel by viewModels()

    private var recordingService: AudioRecordingService? = null
    private var isServiceBound = false

    private val libraryFragment = LibraryFragment()
    private val studioFragment = StudioFragment()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioRecordingService.LocalBinder
            val boundService = binder.getService()
            recordingService = boundService
            boundService.serviceListener = this@MainActivity
            boundService.getPlaybackEngine().listener = this@MainActivity
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isServiceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordGranted) {
            Toast.makeText(this, "Microphone permission required for audio studio.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemWindowInsets()
        checkPermissions()
        bindRecordingService()
        setupNavigation()
        setupRecordDock()
        observePunchAndStudioMode()

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, studioFragment)
            .commit()
    }

    private fun applySystemWindowInsets() {
        val initialDockBottomPadding = binding.bottomDock.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.fragmentContainer.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right
            )
            binding.bottomDock.updatePadding(
                bottom = initialDockBottomPadding + systemBars.bottom
            )
            windowInsets
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun bindRecordingService() {
        val intent = Intent(this, AudioRecordingService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupNavigation() {
        binding.btnNavLibrary.setOnClickListener {
            binding.btnNavLibrary.setTextColor(0xFF448AFF.toInt())
            binding.btnNavStudio.setTextColor(0xFFFFFFFF.toInt())
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, libraryFragment)
                .commit()
        }

        binding.btnNavStudio.setOnClickListener {
            binding.btnNavStudio.setTextColor(0xFF448AFF.toInt())
            binding.btnNavLibrary.setTextColor(0xFFFFFFFF.toInt())
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, studioFragment)
                .commit()
        }
    }

    private fun observePunchAndStudioMode() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    studioViewModel.punchMode.collectLatest { updateDockControls() }
                }
                launch {
                    studioViewModel.studioState.collectLatest { updateDockControls() }
                }
            }
        }
    }

    private fun updateDockControls() {
        val state = studioViewModel.studioState.value
        val mode = studioViewModel.punchMode.value

        if (state == StudioState.RECORDING || state == StudioState.PAUSED) {
            binding.fabRecord.visibility = View.GONE
            binding.layoutActiveControls.visibility = View.VISIBLE
            binding.fabPauseResume.setImageResource(
                if (state == StudioState.RECORDING) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        } else {
            binding.layoutActiveControls.visibility = View.GONE
            binding.fabRecord.visibility = View.VISIBLE

            if (mode == PunchMode.PREVIEW) {
                if (state == StudioState.PREVIEWING) {
                    binding.fabRecord.setImageResource(android.R.drawable.ic_media_pause)
                    binding.fabRecord.backgroundTintList = ColorStateList.valueOf(0xFF00E5FF.toInt())
                } else {
                    binding.fabRecord.setImageResource(android.R.drawable.ic_media_play)
                    binding.fabRecord.backgroundTintList = ColorStateList.valueOf(0xFF00E5FF.toInt())
                }
            } else {
                binding.fabRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
                binding.fabRecord.backgroundTintList = ColorStateList.valueOf(0xFFFF1744.toInt())
            }
        }
    }

    private fun setupRecordDock() {
        binding.fabRecord.setOnClickListener {
            val mode = studioViewModel.punchMode.value
            if (mode == PunchMode.PREVIEW) {
                toggleStudioPreview()
            } else {
                startStudioRecording()
            }
        }

        binding.fabPauseResume.setOnClickListener {
            val state = studioViewModel.studioState.value
            if (state == StudioState.RECORDING) {
                recordingService?.pauseRecording()
                studioViewModel.setStudioState(StudioState.PAUSED)
            } else if (state == StudioState.PAUSED) {
                recordingService?.resumeRecording()
                studioViewModel.setStudioState(StudioState.RECORDING)
            }
        }

        binding.fabStop.setOnClickListener {
            stopStudioRecording()
        }
    }

    fun toggleStudioPreview() {
        val service = recordingService ?: return
        val playback = service.getPlaybackEngine()

        if (studioViewModel.studioState.value == StudioState.PREVIEWING) {
            playback.stopPlayback()
            studioViewModel.setStudioState(StudioState.IDLE)
        } else {
            val rawFile = service.getCrashSentinel().rawFile
            if (!rawFile.exists() || rawFile.length() == 0L) {
                Toast.makeText(this, "No recorded audio to audition yet.", Toast.LENGTH_SHORT).show()
                return
            }

            val preset = studioViewModel.selectedPreset.value
            val startSample = studioViewModel.punchInSampleIndex

            studioViewModel.setStudioState(StudioState.PREVIEWING)
            val success = playback.startPlayback(
                scratchFile = rawFile,
                startSample = startSample,
                sampleRate = preset.sampleRate,
                channels = preset.channels
            )

            if (!success) {
                studioViewModel.setStudioState(StudioState.IDLE)
                Toast.makeText(this, "Could not start timeline audition.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startStudioRecording() {
        val service = recordingService ?: return
        val preset = studioViewModel.selectedPreset.value
        val mic = studioViewModel.selectedMic.value
        val scratchpad = service.getScratchpadManager()

        // Stop any running playback
        service.getPlaybackEngine().stopPlayback()

        val totalBytes = scratchpad.getTotalAudioBytes()
        val totalSamples = totalBytes / (preset.channels * 4L)
        val punchSample = studioViewModel.punchInSampleIndex

        if (punchSample < totalSamples && totalSamples > 0L) {
            // Punch-and-Roll: Shelve downstream tail audio and peaks
            scratchpad.prepareRangeReplacement(punchSample, punchSample, preset.channels)
            studioViewModel.preparePunchWaveform(studioViewModel.punchInPeakIndex)
        } else if (totalSamples == 0L) {
            studioViewModel.clearWaveform()
        }

        val success = service.startRecording(preset, mic)
        if (success) {
            studioViewModel.setStudioState(StudioState.RECORDING)
        } else {
            Toast.makeText(this, "Could not start audio capture engine.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopStudioRecording() {
        val service = recordingService ?: return
        val preset = studioViewModel.selectedPreset.value
        val scratchpad = service.getScratchpadManager()

        service.stopRecording()

        // Splice downstream tail back if it was shelved during punch-in
        if (scratchpad.isTailShelved) {
            scratchpad.spliceTailBack(preset.sampleRate, preset.channels)
            studioViewModel.spliceTailPeaksBack()
        }

        // Export take to permanent WAV file in app recordings directory
        val recordingsDir = File(getExternalFilesDir(null), "recordings").apply { if (!exists()) mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val wavName = "Take_$timeStamp.wav"
        val destWav = File(recordingsDir, wavName)

        scratchpad.exportToFloatWav(destWav, preset.sampleRate, preset.channels)
        service.getCrashSentinel().clearSession()

        val durationMs = service.getElapsedMillis()
        val entity = RecordingEntity(
            title = "Take $timeStamp",
            filePath = destWav.absolutePath,
            durationMs = durationMs,
            sampleRate = preset.sampleRate,
            bitDepth = 32,
            channelCount = preset.channels,
            format = "WAV",
            fileSize = destWav.length()
        )

        CoroutineScope(Dispatchers.IO).launch {
            RecordingRepository.getInstance(this@MainActivity).insertRecording(entity)
        }

        studioViewModel.setStudioState(StudioState.IDLE)
        Toast.makeText(this, "Take saved to Library!", Toast.LENGTH_SHORT).show()
    }

    fun pauseActiveAudioForScrub() {
        if (studioViewModel.studioState.value == StudioState.RECORDING) {
            recordingService?.pauseRecording()
            studioViewModel.setStudioState(StudioState.PAUSED)
        } else if (studioViewModel.studioState.value == StudioState.PREVIEWING) {
            recordingService?.getPlaybackEngine()?.stopPlayback()
            studioViewModel.setStudioState(StudioState.IDLE)
        }
    }

    fun seekScratchpadToSample(sampleIndex: Long, channels: Int) {
        recordingService?.getScratchpadManager()?.seekToSample(sampleIndex, channels)
    }

    fun playRecordingPreview(recording: RecordingEntity) {
        val file = File(recording.filePath)
        recordingService?.getPlaybackEngine()?.startPlayback(
            scratchFile = file,
            startSample = 0L,
            sampleRate = recording.sampleRate,
            channels = recording.channelCount
        )
    }

    fun loadRecordingIntoStudio(recording: RecordingEntity) {
        CoroutineScope(Dispatchers.IO).launch {
            val extractedPeaks = extractWaveformPeaksFromWav(File(recording.filePath), recording.sampleRate, recording.channelCount)
            withContext(Dispatchers.Main) {
                studioViewModel.setWaveformPeaks(extractedPeaks)
                studioViewModel.setElapsedMillis(recording.durationMs)
                binding.btnNavStudio.performClick()
                Toast.makeText(this@MainActivity, "Loaded '${recording.title}'", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun extractWaveformPeaksFromWav(file: File, sampleRate: Int, channels: Int): List<Float> {
        val peaksList = ArrayList<Float>()
        if (!file.exists() || file.length() <= 44L) return peaksList

        try {
            FileInputStream(file).use { fis ->
                fis.skip(44)
                val samplesPerWindow = ((sampleRate * 0.025f) * channels).toInt()
                val byteChunkSize = samplesPerWindow * 4
                val byteBuf = ByteArray(byteChunkSize)
                val direct = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN)

                var read: Int
                while (fis.read(byteBuf).also { read = it } > 0) {
                    val floatsRead = read / 4
                    direct.position(0)
                    var peak = 0.0f
                    for (i in 0 until floatsRead) {
                        val s = abs(direct.getFloat())
                        if (s > peak) peak = s
                    }
                    peaksList.add(peak)
                }
            }
        } catch (_: Exception) {}
        return peaksList
    }

    override fun onPlaybackTick(currentSample: Long, currentMs: Long) {
        runOnUiThread {
            studioViewModel.setElapsedMillis(currentMs)
        }
    }

    override fun onPlaybackFinished() {
        runOnUiThread {
            studioViewModel.setStudioState(StudioState.IDLE)
        }
    }

    override fun onPreRollCountdown(secondsRemaining: Int) {}
    override fun onPreRollFinished() {}

    override fun onAudioFrame(peakDbfs: Float, rmsDbfs: Float, peakLinear: Float) {
        runOnUiThread {
            studioViewModel.updateDbfs(peakDbfs, rmsDbfs)
            if (studioViewModel.studioState.value == StudioState.RECORDING) {
                studioViewModel.addLivePeak(peakLinear)
            }
            recordingService?.let {
                studioViewModel.setElapsedMillis(it.getElapsedMillis())
            }
        }
    }

    override fun onError(errorMessage: String) {
        runOnUiThread {
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
MAIN_ACT

echo "==> 7. Rebuilding and deploying..."
./deploy.sh "Fix Preview playback engine and sample-accurate Punch-and-Roll tail preservation"
