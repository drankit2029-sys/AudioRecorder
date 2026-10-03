package com.studio.audio.core.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class LibraryPlayerManager {

    private var mediaPlayer: MediaPlayer? = null
    private var progressTrackerJob: Job? = null

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

        // Reuse active player if toggling the same track
        if (_currentPlayingFile.value?.absolutePath == file.absolutePath && mediaPlayer != null) {
            mediaPlayer?.let { player ->
                if (startPositionMs > 0L) {
                    player.seekTo(startPositionMs.toInt())
                    _currentPositionMs.value = startPositionMs
                }
                player.start()
                _isPlaying.value = true
                startProgressPolling(scope)
                return
            }
        }

        stop()

        _currentPlayingFile.value = file

        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnCompletionListener {
                _isPlaying.value = false
                _currentPositionMs.value = 0L
                progressTrackerJob?.cancel()
            }
            setOnErrorListener { _, _, _ ->
                stop()
                true
            }
        }

        try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            _totalDurationMs.value = player.duration.toLong().coerceAtLeast(0L)

            if (startPositionMs > 0L) {
                player.seekTo(startPositionMs.toInt())
                _currentPositionMs.value = startPositionMs
            } else {
                _currentPositionMs.value = 0L
            }

            player.start()
            mediaPlayer = player
            _isPlaying.value = true
            startProgressPolling(scope)
        } catch (e: Exception) {
            e.printStackTrace()
            player.release()
            stop()
        }
    }

    private fun startProgressPolling(scope: CoroutineScope) {
        progressTrackerJob?.cancel()
        progressTrackerJob = scope.launch(Dispatchers.Main) {
            while (_isPlaying.value && isActive) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        _currentPositionMs.value = player.currentPosition.toLong().coerceAtLeast(0L)
                    }
                }
                delay(50L) // 20Hz UI progress update
            }
        }
    }

    fun pause() {
        progressTrackerJob?.cancel()
        progressTrackerJob = null
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.pause()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        _isPlaying.value = false
    }

    fun seekTo(
        scope: CoroutineScope,
        positionMs: Long
    ) {
        val target = positionMs.coerceIn(0L, _totalDurationMs.value)
        mediaPlayer?.let { player ->
            try {
                player.seekTo(target.toInt())
                _currentPositionMs.value = target
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        progressTrackerJob?.cancel()
        progressTrackerJob = null

        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mediaPlayer = null
        _isPlaying.value = false
        _currentPositionMs.value = 0L
    }

    fun release() {
        stop()
        _currentPlayingFile.value = null
        _totalDurationMs.value = 0L
    }
}
