package com.aucampro.recorder.audio

/**
 * User-facing audio quality choice (docs/HIRES_AUDIO_DESIGN.md §2/§5, "音声品質" in
 * Settings). [Standard] changes nothing about today's pipeline. The hi-res tiers request a
 * higher *capture* rate from [com.aucampro.recorder.pipeline.RecordingPipeline]'s native
 * audio engine and add a lossless 32-bit float WAV "サイドカー" recorded alongside the
 * existing 48kHz AAC/MP4 track — see that doc's §1 for why it's a separate file rather
 * than a change to the MP4 itself, and §3 for what happens when the connected input device
 * can't actually deliver the requested rate (never a hard failure: the engine falls back
 * down this same rate ladder to [Standard]'s rate, and the achieved rate is surfaced to the
 * UI rather than silently assumed).
 */
enum class AudioQuality(val label: String, val sampleRateHz: Int) {
    Standard("標準 (48kHz)", 48_000),
    HiRes96("ハイレゾ 96kHz", 96_000),
    HiRes192("ハイレゾ 192kHz", 192_000),
}
