package com.studio.audio.core.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class LibraryPlayerManager(
    private val renderer: PcmHardwareRenderer = PcmHardwareRenderer()
) {
    private var streamingJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPlayingFile = MutableStateFlow<File?>(null)
    val currentPlayingFile: StateFlow<File?> = _currentPlayingFile.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _totalDurationMs = MutableStateFlow(0L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs.asStateFlow()

    fun play(
        scope: CoroutineScope,
        file: File,
        startPositionMs: Long = 0L
    ) {
        if (!file.exists() || file.length() == 0L) return

        stop()

        _currentPlayingFile.value = file
        val fileLengthBytes = file.length()
        val calculatedDurationMs = (fileLengthBytes * 1000L) / renderer.bytesPerSecond
        _totalDurationMs.value = calculatedDurationMs

        // Snap target byte offset to 4-byte float boundary
        val targetByteOffset = ((startPositionMs * renderer.bytesPerSecond) / 1000L)
            .coerceIn(0L, fileLengthBytes)
            .let { (it / renderer.bytesPerSample) * renderer.bytesPerSample }

        _currentPositionMs.value = (targetByteOffset * 1000L) / renderer.bytesPerSecond

        val initialized = renderer.initializeHardware()
        if (!initialized) return

        renderer.flush()
        val started = renderer.play()
        if (!started) return

        isRunning.set(true)
        _isPlaying.value = true

        streamingJob = scope.launch(Dispatchers.IO) {
            var randomAccessFile: RandomAccessFile? = null
            try {
                randomAccessFile = RandomAccessFile(file, "r")
                randomAccessFile.seek(targetByteOffset)

                val readChunkSize = 4096
                val byteBuffer = ByteBuffer.allocateDirect(readChunkSize).order(ByteOrder.LITTLE_ENDIAN)
                val tempByteArray = ByteArray(readChunkSize)
                var currentBytesReadOffset = targetByteOffset

                while (isRunning.get() && isActive) {
                    val bytesRead = randomAccessFile.read(tempByteArray, 0, tempByteArray.size)

                    if (bytesRead > 0) {
                        byteBuffer.clear()
                        byteBuffer.put(tempByteArray, 0, bytesRead)
                        byteBuffer.position(0)

                        renderer.write(byteBuffer, bytesRead)

                        currentBytesReadOffset += bytesRead
                        val progressMs = (currentBytesReadOffset * 1000L) / renderer.bytesPerSecond
                        _currentPositionMs.value = progressMs
                    } else {
                        // EOF reached: gracefully terminate playback loop
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                randomAccessFile?.close()
                withContext(Dispatchers.Main) {
                    stop()
                }
            }
        }
    }

    fun pause() {
        if (!isRunning.get()) return
        isRunning.set(false)
        _isPlaying.value = false
        streamingJob?.cancel()
        streamingJob = null
        renderer.pause()
    }

    fun seekTo(
        scope: CoroutineScope,
        positionMs: Long
    ) {
        val file = _currentPlayingFile.value ?: return
        val wasPlaying = _isPlaying.value
        play(scope, file, positionMs)
        if (!wasPlaying) {
            pause()
            _currentPositionMs.value = positionMs
        }
    }

    fun stop() {
        isRunning.set(false)
        _isPlaying.value = false
        streamingJob?.cancel()
        streamingJob = null
        renderer.stop()
    }

    fun release() {
        stop()
        renderer.release()
        _currentPlayingFile.value = null
        _currentPositionMs.value = 0L
        _totalDurationMs.value = 0L
    }
}