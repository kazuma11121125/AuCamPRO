package com.procamera.recorder.muxer

/**
 * Normalizes Video and Audio presentation timestamps onto a single epoch (recording
 * start = 0), in the units MediaCodec/MediaMuxer expect (microseconds). This is §4.3,
 * the spec's "最重要要件" (most important requirement) — see docs/ARCHITECTURE.md's PTS
 * synchronization design section for the full derivation this class implements.
 *
 * Not thread-safe: callers must confine video-side calls to the Video Encoder Callback
 * thread and audio-side calls to the Audio Encoder thread (matching the thread model in
 * docs/ARCHITECTURE.md); the two sides never touch shared mutable state with each other
 * except the immutable [recordingStartNanos] captured once in [start].
 */
class PtsClockDomain(private val clock: Clock = SystemClockAdapter) {

    interface Clock {
        /** CLOCK_MONOTONIC — this class's chosen common reference domain for both tracks. */
        fun nanoTimeNanos(): Long

        /** CLOCK_BOOTTIME — what SENSOR_TIMESTAMP equals under TIMESTAMP_SOURCE_REALTIME. */
        fun elapsedRealtimeNanos(): Long
    }

    object SystemClockAdapter : Clock {
        override fun nanoTimeNanos(): Long = System.nanoTime()
        override fun elapsedRealtimeNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
    }

    sealed interface TimestampSource {
        /** SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME. */
        data object Realtime : TimestampSource

        /** SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN — needs the calibration below. */
        data object Unknown : TimestampSource
    }

    companion object {
        /** §4.3's concrete calibration proposal for the UNKNOWN clock source. */
        const val UNKNOWN_CALIBRATION_SAMPLE_COUNT = 10
    }

    private var timestampSource: TimestampSource? = null
    private var recordingStartNanos: Long = 0L

    // --- Realtime calibration: exact, single reading ---
    // Derivation: elapsedRealtimeNanos(t) - nanoTimeNanos(t) is constant (call it C) for as
    // long as the device never suspends (guaranteed during recording by the Foreground
    // Service's WAKE_LOCK, §4.6). SENSOR_TIMESTAMP under REALTIME equals
    // elapsedRealtimeNanos() at the actual hardware capture instant: s = E(t_frame).
    // We want N(t_frame) (our common reference domain): N(t_frame) = E(t_frame) - C
    // = s - C = s - (E0 - N0), where E0/N0 are a single back-to-back reading taken at
    // calibration time. So: monotonicNanos = sensorTimestampNanos - realtimeOffsetNanos,
    // with realtimeOffsetNanos = E0 - N0.
    private var realtimeOffsetNanos: Long = 0L

    // --- Unknown calibration: statistical, K-sample median (see class doc) ---
    private val unknownCalibrationSamples = mutableListOf<Long>()
    private var unknownOffsetNanos: Long? = null

    private var lastVideoPtsUs: Long = Long.MIN_VALUE
    private var audioAnchorNanos: Long = -1L
    private var lastAudioPtsUs: Long = Long.MIN_VALUE

    /** Call once, right when recording starts, before any PTS normalization. */
    fun start(source: TimestampSource, nowNanos: Long = clock.nanoTimeNanos()) {
        timestampSource = source
        recordingStartNanos = nowNanos
        unknownCalibrationSamples.clear()
        unknownOffsetNanos = null
        lastVideoPtsUs = Long.MIN_VALUE
        lastAudioPtsUs = Long.MIN_VALUE
        audioAnchorNanos = -1L

        if (source is TimestampSource.Realtime) {
            val e0 = clock.elapsedRealtimeNanos()
            val n0 = clock.nanoTimeNanos()
            realtimeOffsetNanos = e0 - n0
        }
    }

    /**
     * UNKNOWN source only: feed one (sensorTimestamp, appArrivalTime) pair per frame from
     * `CameraCaptureSession.CaptureCallback#onCaptureCompleted` until [isVideoCalibrated]
     * becomes true (after [UNKNOWN_CALIBRATION_SAMPLE_COUNT] samples). No-op once
     * calibrated, and no-op for the Realtime source (which needs no per-frame samples).
     */
    fun addUnknownCalibrationSample(sensorTimestampNanos: Long, arrivalNanoTime: Long = clock.nanoTimeNanos()) {
        if (timestampSource !== TimestampSource.Unknown) return
        if (unknownOffsetNanos != null) return
        unknownCalibrationSamples += (arrivalNanoTime - sensorTimestampNanos)
        if (unknownCalibrationSamples.size >= UNKNOWN_CALIBRATION_SAMPLE_COUNT) {
            unknownOffsetNanos = median(unknownCalibrationSamples)
        }
    }

    val isVideoCalibrated: Boolean
        get() = when (timestampSource) {
            TimestampSource.Realtime -> true
            TimestampSource.Unknown -> unknownOffsetNanos != null
            null -> false
        }

    /**
     * Normalizes one video frame's SENSOR_TIMESTAMP into an epoch-zeroed microsecond PTS
     * suitable for MediaCodec. Returns null if the frame must be dropped: either
     * calibration isn't ready yet (UNKNOWN source, still collecting samples — frames
     * arriving before that point are expected to be absorbed by the Muxer's pending-queue
     * per §4.4, not lost) or the computed PTS would not be strictly greater than the last
     * emitted one (the monotonic-increase guard from §4.3's last bullet).
     */
    fun normalizeVideoPtsUs(sensorTimestampNanos: Long): Long? {
        val monotonicNanos = when (timestampSource) {
            TimestampSource.Realtime -> sensorTimestampNanos - realtimeOffsetNanos
            TimestampSource.Unknown -> {
                val offset = unknownOffsetNanos ?: return null
                sensorTimestampNanos + offset
            }

            null -> error("PtsClockDomain.start() must be called before normalizeVideoPtsUs()")
        }
        // A frame captured fractionally before start() (e.g. already in flight when
        // recording began) would otherwise map to a negative PTS, which MediaMuxer
        // rejects outright — clamp to the epoch boundary instead of emitting it raw.
        val ptsUs = ((monotonicNanos - recordingStartNanos) / 1000L).coerceAtLeast(0L)
        if (ptsUs <= lastVideoPtsUs) {
            return null
        }
        lastVideoPtsUs = ptsUs
        return ptsUs
    }

    /**
     * Call once when the first audio burst is received, anchoring the drift-free
     * sample-count-based Audio PTS basis (§4.3).
     *
     * Prefer [startAudioAnchorFromFrameCorrelation] over this raw form when a real
     * frame-position/time correlation is available (from
     * `NativeEngineBridge.getInputTimestamp()`): anchoring to "now" at the moment the
     * first callback happens to be *processed* dates sample 0 to callback wall-time,
     * which trails the sample's true capture instant by the audio input pipeline's
     * latency (tens of ms on real hardware) — a constant offset that silently eats into
     * the ±20ms A/V sync budget from frame one, even though the sample-count-based PTS
     * math downstream stays perfectly drift-free. This overload remains as the
     * documented fallback for when a correlation isn't available yet.
     */
    fun startAudioAnchor(nowNanos: Long = clock.nanoTimeNanos()) {
        audioAnchorNanos = nowNanos
    }

    /**
     * Anchors using a real (framePosition, timeNanos) correlation at CLOCK_MONOTONIC —
     * see [startAudioAnchor]'s doc for why this is preferred. Back-calculates the true
     * capture time of sample 0: anchor = timeNanos - framePosition/sampleRateHz.
     */
    fun startAudioAnchorFromFrameCorrelation(framePosition: Long, timeNanos: Long, sampleRateHz: Int) {
        val framePositionDurationNanos = framePosition * 1_000_000_000L / sampleRateHz
        startAudioAnchor(nowNanos = timeNanos - framePositionDurationNanos)
    }

    /**
     * Normalizes the cumulative sample count consumed so far into an epoch-zeroed
     * microsecond PTS. [cumulativeSampleCount] must be a running total (per-channel frame
     * count, i.e. not multiplied by channel count) since [startAudioAnchor]. Returns null
     * if the computed PTS would not be strictly greater than the last emitted one.
     */
    fun normalizeAudioPtsUs(cumulativeSampleCount: Long, sampleRateHz: Int): Long? {
        check(audioAnchorNanos >= 0) { "startAudioAnchor() must be called before normalizeAudioPtsUs()" }
        val elapsedNanos = cumulativeSampleCount * 1_000_000_000L / sampleRateHz
        val monotonicNanos = audioAnchorNanos + elapsedNanos
        val ptsUs = ((monotonicNanos - recordingStartNanos) / 1000L).coerceAtLeast(0L)
        if (ptsUs <= lastAudioPtsUs) {
            return null
        }
        lastAudioPtsUs = ptsUs
        return ptsUs
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2
        } else {
            sorted[mid]
        }
    }
}
