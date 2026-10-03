package com.studio.audio.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer

class PcmHardwareRenderer(
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
    private val encoding: Int = AudioFormat.ENCODING_PCM_FLOAT
) {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    val bytesPerSample: Int = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_16BIT -> 2
        else -> 4
    }

    val channelCount: Int = when (channelConfig) {
        AudioFormat.CHANNEL_OUT_MONO -> 1
        AudioFormat.CHANNEL_OUT_STEREO -> 2
        else -> 1
    }

    val bytesPerSecond: Long = sampleRate * channelCount * bytesPerSample.toLong()

    @Synchronized
    fun initializeHardware(): Boolean {
        if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            return true
        }

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBufferSize <= 0) return false

        val bufferSizeBytes = (minBufferSize * 2).coerceAtLeast(8192)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return false

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return false
        }

        audioTrack = track
        return true
    }

    @Synchronized
    fun play(): Boolean {
        if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            if (!initializeHardware()) return false
        }

        val track = audioTrack ?: return false
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            isPlaying = true
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    @Synchronized
    fun pause() {
        isPlaying = false
        audioTrack?.apply {
            try {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    pause()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Clears hardware queue so seeks or real-time stream resets respond instantly.
     */
    @Synchronized
    fun flush() {
        audioTrack?.apply {
            try {
                flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    fun write(byteBuffer: ByteBuffer, sizeBytes: Int): Int {
        val track = audioTrack ?: return -1
        if (!isPlaying || track.playState != AudioTrack.PLAYSTATE_PLAYING) return -1
        return track.write(byteBuffer, sizeBytes, AudioTrack.WRITE_BLOCKING)
    }

    @Synchronized
    fun write(byteArray: ByteArray, offset: Int, sizeBytes: Int): Int {
        val track = audioTrack ?: return -1
        if (!isPlaying || track.playState != AudioTrack.PLAYSTATE_PLAYING) return -1
        return track.write(byteArray, offset, sizeBytes, AudioTrack.WRITE_BLOCKING)
    }

    @Synchronized
    fun stop() {
        isPlaying = false
        audioTrack?.apply {
            try {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
                flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun isHardwareActive(): Boolean = isPlaying
}