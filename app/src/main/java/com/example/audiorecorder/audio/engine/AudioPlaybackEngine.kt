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
        isPlaying.set(false)
        isPaused.set(false)

        try {
            audioTrack?.stop()
            audioTrack?.flush()
        } catch (_: Exception) {}
        release()

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
            if (Thread.currentThread() != playbackThread) {
                playbackThread?.interrupt()
                playbackThread?.join(250)
            }
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
