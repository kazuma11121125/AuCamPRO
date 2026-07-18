package com.aucampro.recorder.encoder

/**
 * Owns the hi-res WAV "サイドカー" output (docs/HIRES_AUDIO_DESIGN.md §1/§6.2/§6.4) — a
 * plain, lossless 32-bit float WAV recorded alongside (never instead of) the existing
 * AAC/MP4 path. Driven entirely from [AudioEncoder]'s own drain thread, the same float
 * blocks it reads from the ring buffer for the AAC path — never a second ring-buffer
 * consumer (see [AudioEncoder]'s SPSC single-consumer invariant doc).
 *
 * Segments purely on elapsed time (no keyframe constraint, unlike
 * [com.aucampro.recorder.muxer.SegmentedMuxerController]'s video-driven rotation) so each
 * `.wav`'s RIFF size fields (32-bit, ~4GB ceiling) stay comfortably bounded — see
 * docs/HIRES_AUDIO_DESIGN.md §6.4.
 */
class HiResAudioSink(
    private val outputPathForSegment: (index: Int) -> String,
    private val sampleRateHz: Int,
    private val channelCount: Int,
    private val segmentDurationUs: Long,
) {
    private var segmentIndex = 0
    private var writer = WavFileWriter(outputPathForSegment(segmentIndex), sampleRateHz, channelCount)
    private var samplesWrittenInSegment = 0L

    /** Drain-thread only (see class doc). Rotates to a new segment file first if the
     * current one has already reached [segmentDurationUs], then writes. */
    fun writeFrames(interleaved: FloatArray, frameCount: Int) {
        val segmentElapsedUs = samplesWrittenInSegment * 1_000_000L / sampleRateHz
        if (samplesWrittenInSegment > 0 && segmentElapsedUs >= segmentDurationUs) {
            rotate()
        }
        writer.writeFrames(interleaved, frameCount)
        samplesWrittenInSegment += frameCount
    }

    private fun rotate() {
        writer.close()
        segmentIndex++
        writer = WavFileWriter(outputPathForSegment(segmentIndex), sampleRateHz, channelCount)
        samplesWrittenInSegment = 0L
    }

    /** Drain-thread only. Idempotent via [WavFileWriter.close]'s own idempotency — safe to
     * call from both the normal stop path and a crash-safety finalize path (which may run
     * on a different thread mid-write; same accepted best-effort risk as
     * [com.aucampro.recorder.pipeline.RecordingPipeline.emergencyFinalizeCurrentSegment]'s
     * muxer finalize — goal is a playable file, not a guaranteed-complete one). */
    fun close() {
        writer.close()
    }
}
