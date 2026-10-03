package com.studio.audio.core.audio

import android.media.MediaCodecList
import android.media.MediaFormat

enum class AudioEncodingFormat(
    val label: String,
    val extension: String,
    val mimeType: String?,
    val isRawPcm: Boolean
) {
    WAV_PCM_16("WAV (16-bit PCM)", "wav", null, true),
    WAV_PCM_24("WAV (24-bit PCM)", "wav", null, true),
    WAV_PCM_FLOAT("WAV (32-bit Float)", "wav", null, true),
    AAC_LC("AAC-LC (Compressed)", "m4a", MediaFormat.MIMETYPE_AUDIO_AAC, false),
    OPUS("Opus (Compressed)", "opus", MediaFormat.MIMETYPE_AUDIO_OPUS, false),
    FLAC("FLAC (Lossless)", "flac", MediaFormat.MIMETYPE_AUDIO_FLAC, false)
}

data class AudioPreset(
    val id: String,
    val name: String,
    val description: String,
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: String,
    val format: AudioEncodingFormat,
    val isCustom: Boolean = false
)

data class CompatibilityResult(
    val isSupported: Boolean,
    val unsupportedReasons: List<String>
)

object AudioPresetValidator {

    val AVAILABLE_SAMPLE_RATES = listOf(
        8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000, 176400, 192000
    )

    val AVAILABLE_CHANNEL_COUNTS = listOf(
        1 to "Mono",
        2 to "Stereo",
        4 to "4ch",
        6 to "5.1 Surround",
        8 to "8ch"
    )

    val AVAILABLE_BIT_DEPTHS = listOf("16-bit", "24-bit", "32-bit Float")

    val POPULAR_PRESETS = listOf(
        AudioPreset(
            id = "preset_standard",
            name = "Standard Voice",
            description = "Lightweight mono capture for voice notes & transcripts",
            sampleRate = 44100,
            channelCount = 1,
            bitDepth = "16-bit",
            format = AudioEncodingFormat.WAV_PCM_16
        ),
        AudioPreset(
            id = "preset_podcast",
            name = "Podcast Studio",
            description = "48 kHz broadcast standard with stereo imaging",
            sampleRate = 48000,
            channelCount = 2,
            bitDepth = "24-bit",
            format = AudioEncodingFormat.WAV_PCM_24
        ),
        AudioPreset(
            id = "preset_raw_wav",
            name = "Raw Studio (32-bit Float)",
            description = "Full dynamic range without digital clipping risk",
            sampleRate = 48000,
            channelCount = 2,
            bitDepth = "32-bit Float",
            format = AudioEncodingFormat.WAV_PCM_FLOAT
        ),
        AudioPreset(
            id = "preset_highres",
            name = "High-Res Archival (96kHz)",
            description = "Mastering-grade fidelity for acoustic capture",
            sampleRate = 96000,
            channelCount = 2,
            bitDepth = "24-bit",
            format = AudioEncodingFormat.WAV_PCM_24
        ),
        AudioPreset(
            id = "preset_broadcast_aac",
            name = "Broadcast AAC",
            description = "Compressed streaming standard encoded by Android OS",
            sampleRate = 48000,
            channelCount = 2,
            bitDepth = "16-bit",
            format = AudioEncodingFormat.AAC_LC
        ),
        AudioPreset(
            id = "preset_voice_opus",
            name = "Speech Optimized Opus",
            description = "Low-bitrate speech codec encoded by Android OS",
            sampleRate = 48000,
            channelCount = 1,
            bitDepth = "16-bit",
            format = AudioEncodingFormat.OPUS
        )
    )

    fun validate(preset: AudioPreset, device: AudioInputDevice?): CompatibilityResult {
        val reasons = mutableListOf<String>()

        if (device != null && !device.isUnconstrained && device.sampleRates.isNotEmpty()) {
            if (preset.sampleRate !in device.sampleRates) {
                reasons.add(
                    "Hardware Mic does not support ${preset.sampleRate} Hz (Supported: ${device.sampleRates.sorted().joinToString { "${it}Hz" }})"
                )
            }
        }

        if (device != null && device.channelCounts.isNotEmpty()) {
            if (preset.channelCount !in device.channelCounts) {
                val label = if (preset.channelCount == 1) "Mono" else if (preset.channelCount == 2) "Stereo" else "${preset.channelCount} Channels"
                reasons.add(
                    "Hardware Mic does not support $label (Supported: ${device.channelCounts.sorted().joinToString { "${it} ch" }})"
                )
            }
        }

        if (!preset.format.isRawPcm && preset.format.mimeType != null) {
            val hasSystemEncoder = checkSystemEncoderSupport(preset.format.mimeType)
            if (!hasSystemEncoder) {
                reasons.add(
                    "Android OS MediaCodec has no encoder for ${preset.format.label}"
                )
            }
        }

        return CompatibilityResult(
            isSupported = reasons.isEmpty(),
            unsupportedReasons = reasons
        )
    }

    private fun checkSystemEncoderSupport(mimeType: String): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        } catch (_: Exception) {
            false
        }
    }
}
