package com.example.audiorecorder.audio.hardware

enum class PresetType {
    STANDARD_PODCAST,
    STUDIO_MASTER,
    VOICE_MEMO,
    CUSTOM
}

data class AudioPreset(
    val type: PresetType,
    val name: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int, // 1 for Mono, 2 for Stereo
    val containerFormat: String // "WAV", "FLAC", "AAC", "MP3"
) {
    companion object {
        val STANDARD_PODCAST = AudioPreset(
            type = PresetType.STANDARD_PODCAST,
            name = "Standard Podcast",
            sampleRate = 48000,
            bitDepth = 24,
            channels = 2,
            containerFormat = "WAV"
        )

        val STUDIO_MASTER = AudioPreset(
            type = PresetType.STUDIO_MASTER,
            name = "Studio Master (Raw Float)",
            sampleRate = 48000, // May scale to 96000 if hardware supports it
            bitDepth = 32,
            channels = 2,
            containerFormat = "WAV"
        )

        val VOICE_MEMO = AudioPreset(
            type = PresetType.VOICE_MEMO,
            name = "Voice Memo",
            sampleRate = 16000,
            bitDepth = 16,
            channels = 1,
            containerFormat = "AAC"
        )

        fun getDefaults(): List<AudioPreset> = listOf(
            STANDARD_PODCAST,
            STUDIO_MASTER,
            VOICE_MEMO
        )
    }
}
