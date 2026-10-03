package com.studio.audio.core.audio

import android.media.MediaCodecList
import android.media.MediaFormat

enum class BitDepth(val label: String, val bitCount: Int) {
    BIT_16("16-bit", 16),
    BIT_24("24-bit", 24),
    FLOAT_32("32-bit Float", 32);

    override fun toString(): String = label
}

enum class AudioFormatType(
    val label: String,
    val extension: String,
    val mimeType: String?,
    val isRawPcm: Boolean,
    val supportedBitDepths: List<BitDepth>
) {
    WAV(
        label = "WAV (Uncompressed PCM)",
        extension = "wav",
        mimeType = null,
        isRawPcm = true,
        supportedBitDepths = listOf(BitDepth.BIT_16, BitDepth.BIT_24, BitDepth.FLOAT_32)
    ),
    FLAC(
        label = "FLAC (Lossless)",
        extension = "flac",
        mimeType = MediaFormat.MIMETYPE_AUDIO_FLAC,
        isRawPcm = false,
        supportedBitDepths = listOf(BitDepth.BIT_16, BitDepth.BIT_24)
    ),
    AAC(
        label = "AAC-LC (Compressed)",
        extension = "m4a",
        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
        isRawPcm = false,
        supportedBitDepths = emptyList() // Lossy: Bit-depth does not apply
    ),
    OPUS(
        label = "Opus (Compressed)",
        extension = "opus",
        mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS,
        isRawPcm = false,
        supportedBitDepths = emptyList() // Lossy: Fixed perceptual representation
    )
}

// Backward-compatible type alias
typealias AudioEncodingFormat = AudioFormatType

data class AudioPreset(
    val id: String,
    val name: String,
    val description: String,
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: BitDepth?,
    val format: AudioFormatType,
    val isCustom: Boolean = false
) {
    val displayBitDepth: String
        get() = bitDepth?.label ?: "Compressed"
}

data class CompatibilityResult(
    val isSupported: Boolean,
    val unsupportedReasons: List<String>
)

object AudioPresetValidator {

    val AVAILABLE_SAMPLE_RATES = listOf(
        8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000, 176400, 192000
    )

    val AVAILABLE_CHANNEL_COUNTS = listOf(
        1 to "1 (Mono)",
        2 to "2 (Stereo)",
        4 to "4 (Quadraphonic)",
        6 to "6 (5.1 Surround)",
        8 to "8 (Octa / Multi-channel)"
    )

    val POPULAR_PRESETS = listOf(
        AudioPreset(
            id = "preset_standard",
            name = "Standard Voice",
            description = "Lightweight mono capture for voice notes & transcripts",
            sampleRate = 44100,
            channelCount = 1,
            bitDepth = BitDepth.BIT_16,
            format = AudioFormatType.WAV
        ),
        AudioPreset(
            id = "preset_podcast",
            name = "Podcast Studio",
            description = "48 kHz broadcast standard with stereo imaging",
            sampleRate = 48000,
            channelCount = 2,
            bitDepth = BitDepth.BIT_24,
            format = AudioFormatType.WAV
        ),
        AudioPreset(
            id = "preset_raw_wav",
            name = "Raw Studio (32-bit Float)",
            description = "Full dynamic range without digital clipping risk",
            sampleRate = 48000,
            channelCount = 2,
            bitDepth = BitDepth.FLOAT_32,
            format = AudioFormatType.WAV
        ),
        AudioPreset(
            id = "preset_highres",
            name = "High-Res Archival (96kHz)",
            description = "Mastering-grade fidelity for acoustic capture",
            sampleRate = 96000,
            channelCount = 2,
            bitDepth = BitDepth.BIT_24,
            format = AudioFormatType.WAV
        ),
        AudioPreset(
            id = "preset_broadcast_aac",
            name = "Broadcast AAC",
            description = "Compressed streaming standard encoded by Android OS",
            sampleRate = 48000,
            channelCount = 2,
            bitDepth = null,
            format = AudioFormatType.AAC
        ),
        AudioPreset(
            id = "preset_voice_opus",
            name = "Speech Optimized Opus",
            description = "Low-bitrate speech codec encoded by Android OS",
            sampleRate = 48000,
            channelCount = 1,
            bitDepth = null,
            format = AudioFormatType.OPUS
        )
    )

    fun validate(preset: AudioPreset, device: AudioInputDevice?): CompatibilityResult {
        val reasons = mutableListOf<String>()

        // 1. FORMAT & BIT-DEPTH COMPATIBILITY CHECK
        if (preset.format.supportedBitDepths.isNotEmpty()) {
            if (preset.bitDepth == null) {
                reasons.add("${preset.format.label} requires an explicit bit-depth selection.")
            } else if (preset.bitDepth !in preset.format.supportedBitDepths) {
                reasons.add(
                    "${preset.format.label} does not support ${preset.bitDepth.label}. Supported: ${preset.format.supportedBitDepths.joinToString { it.label }}"
                )
            }
        } else {
            if (preset.bitDepth != null) {
                reasons.add("${preset.format.label} is a lossy compressed codec; bit-depth selection is not applicable.")
            }
        }

        // 2. CODEC SPECIFIC CONSTRAINTS (e.g. Opus sample rates)
        if (preset.format == AudioFormatType.OPUS) {
            val validOpusRates = listOf(8000, 12000, 16000, 24000, 48000)
            if (preset.sampleRate !in validOpusRates) {
                reasons.add("Opus encoder requires 8k, 12k, 16k, 24k, or 48kHz (selected: ${preset.sampleRate}Hz)")
            }
        }

        // 3. HARDWARE MIC CHECK: Sampling rate
        if (device != null && !device.isUnconstrained && device.sampleRates.isNotEmpty()) {
            if (preset.sampleRate !in device.sampleRates) {
                reasons.add(
                    "Hardware Mic does not support ${preset.sampleRate} Hz (Supported: ${device.sampleRates.sorted().joinToString { "${it}Hz" }})"
                )
            }
        }

        // 4. HARDWARE MIC CHECK: Channel layout
        if (device != null && device.channelCounts.isNotEmpty()) {
            if (preset.channelCount !in device.channelCounts) {
                val label = if (preset.channelCount == 1) "Mono" else if (preset.channelCount == 2) "Stereo" else "${preset.channelCount} Channels"
                reasons.add(
                    "Hardware Mic does not support $label (Supported: ${device.channelCounts.sorted().joinToString { "${it} ch" }})"
                )
            }
        }

        // 5. SYSTEM OS CODEC CHECK
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