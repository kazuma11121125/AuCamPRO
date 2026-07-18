package com.aucampro.recorder.audio

/**
 * Kotlin-facing wrapper around the native `OboeFullDuplexEngine` (see
 * `app/src/main/cpp/engine/OboeFullDuplexEngine.h`). One instance owns exactly one native
 * engine object (handle pattern); callers must call [close] exactly once when done (the
 * Foreground Service does this in Phase 4).
 *
 * All methods here run on a normal JVM thread and are safe to call from
 * Dispatchers.Default/IO coroutines — none of them touch the audio callback thread
 * directly (that thread lives entirely inside the native layer, per §4.2).
 */
class NativeEngineBridge : AutoCloseable {
    private val handle: Long = nativeCreate()

    // Volatile, not a plain var: real-device finding — the meter poll loop
    // (CameraControlViewModel's ~30fps meterJob) reads peakDb/rmsDb from a coroutine
    // dispatcher thread while close() (RecordingPipeline.stopAll(), from the ViewModel's
    // onCleared()) can run concurrently on another; a plain var risks the poll thread
    // never observing the flip and calling into a native handle nativeDestroy() already
    // deleted (use-after-free). This flag is still just a TOCTOU-prone fast path (a call
    // can race past this check into a real JNI call after close() has started) — what
    // actually closes that window now is `native-lib.cpp`'s `EngineGuard`/`g_registry`
    // (added 2026-07-17 after a real tombstone in AudioEncoder's drain thread — see
    // PERF_INVESTIGATION_2026-07-17.md §3.1): nativeDestroy() can't free the engine until
    // every JNI call already past this flag has returned, so the race this flag can still
    // lose is merely "one extra call reaches native code before seeing closed==true", not
    // "that call touches freed memory".
    @Volatile private var closed = false

    /** [preferredInputDeviceId] of 0 means "let the OS choose" (oboe::kUnspecified).
     * [requestedSampleRateHz] drives the hi-res fallback ladder
     * (docs/HIRES_AUDIO_DESIGN.md §3) — always succeeds at some rate (never fails purely
     * because a hi-res rate wasn't available); call [actualSampleRateHz] afterward to see
     * what was actually granted. */
    fun start(preferredInputDeviceId: Int = kUnspecifiedDeviceId, requestedSampleRateHz: Int = kStandardSampleRateHz): String? =
        nativeStart(handle, preferredInputDeviceId, requestedSampleRateHz)

    /** The engine's actual current sample rate (after any hi-res fallback) — see [start].
     * 0 once [closed]. */
    fun actualSampleRateHz(): Int = if (closed) 0 else nativeGetActualSampleRate(handle)

    fun stop() = nativeStop(handle)

    fun reopenInputStream(deviceId: Int): String? = nativeReopenInputStream(handle, deviceId)

    fun insertSilence(frameCount: Int) = nativeInsertSilence(handle, frameCount)

    fun setMonitoringEnabled(enabled: Boolean, outputDeviceId: Int = kUnspecifiedDeviceId): String? =
        nativeSetMonitoringEnabled(handle, enabled, outputDeviceId)

    fun setEqBandParams(band: Int, freqHz: Float, q: Float, gainDb: Float) =
        nativeSetEqBandParams(handle, band, freqHz, q, gainDb)

    /**
     * Manual record-level (input gain) control — see `dsp/InputGain.h`'s doc for what
     * this is (a post-ADC digital gain, applied before the EQ/limiter) and its limits
     * (cannot undo analog/mic-stage clipping).
     */
    fun setInputGainDb(gainDb: Float) = nativeSetInputGainDb(handle, gainDb)

    /**
     * Optional post-EQ loudness boost, default 0dB/bypass — see `dsp/MakeupGain.h`'s doc
     * for how this differs from [setInputGainDb] (opposite end of the gain-staging range).
     */
    fun setMakeupGainDb(gainDb: Float) = nativeSetMakeupGainDb(handle, gainDb)

    /** §4.2 風切り音/ハンドリングノイズ対策のローカット — see `dsp/HighPassFilter.h`'s doc. */
    fun setHighPassEnabled(enabled: Boolean) = nativeSetHighPassEnabled(handle, enabled)
    fun setHighPassCutoffHz(cutoffHz: Float) = nativeSetHighPassCutoffHz(handle, cutoffHz)

    /** [channel]: 0 = left, 1 = right — see `dsp/PeakRmsMeter.h`'s doc for why L/R are tracked independently. */
    fun peakDb(channel: Int): Float = if (closed) SILENCE_DB else nativePeakDb(handle, channel)

    fun rmsDb(channel: Int): Float = if (closed) SILENCE_DB else nativeRmsDb(handle, channel)

    fun ringBufferOverrunCount(): Int = if (closed) 0 else nativeRingBufferOverrunCount(handle)

    fun hardwareXRunCount(): Int = if (closed) 0 else nativeHardwareXRunCount(handle)

    /** Diagnostic (2026-07-18, monitor-noise investigation) — kept permanently, same as ringBufferOverrunCount/hardwareXRunCount — see
     * `OboeFullDuplexEngine::monitorWriteShortfallCount`'s doc. */
    fun monitorWriteShortfallCount(): Int = if (closed) 0 else nativeMonitorWriteShortfallCount(handle)

    /**
     * One-shot (framePosition, timeNanos) correlation at CLOCK_MONOTONIC, used to seed
     * [com.aucampro.recorder.muxer.PtsClockDomain]'s audio anchor with the audio
     * pipeline's true capture-time basis rather than a callback's wall-clock arrival time
     * (see the native-side doc comment for why that distinction matters for §4.3's A/V
     * sync budget). Returns null if not yet available (e.g. queried too soon after
     * [start]) — callers should retry rather than fall back silently, since falling back
     * to callback-arrival-time anchoring reintroduces the input-latency offset this exists
     * to avoid.
     */
    fun getInputTimestamp(): Pair<Long, Long>? {
        val raw = nativeGetInputTimestamp(handle) ?: return null
        return raw[0] to raw[1]
    }

    /** Drains up to [maxFrames] stereo frames into [dst] (must be sized >= maxFrames*2).
     * Returns 0 once [closed] (matching an empty-ring-buffer read) rather than making the
     * JNI call at all — [AudioEncoder]'s drain thread can still be calling this after
     * [close] has already run on another thread (its final drain loop keeps going past
     * `stop()`'s join timeout — see [AudioEncoder.stop]'s doc); the native side's own
     * handle-registry lock (`native-lib.cpp`'s `EngineGuard`) is what actually makes that
     * safe against a freed engine, this is just skipping the now-guaranteed-no-op call. */
    fun drainEncoderBuffer(dst: FloatArray, maxFrames: Int): Int =
        if (closed) 0 else nativeDrainEncoderBuffer(handle, dst, maxFrames)

    /** Discards any stale backlog (see `OboeFullDuplexEngine::flushRingBuffer`'s doc) —
     * call before a fresh [com.aucampro.recorder.encoder.AudioEncoder] starts draining. */
    fun flushRingBuffer() {
        if (!closed) nativeFlushRingBuffer(handle)
    }

    override fun close() {
        if (!closed) {
            nativeStop(handle)
            nativeDestroy(handle)
            closed = true
        }
    }

    private companion object {
        const val kUnspecifiedDeviceId = 0 // matches oboe::kUnspecified
        const val kStandardSampleRateHz = 48_000 // matches OboeFullDuplexEngine::kStandardSampleRate
        const val SILENCE_DB = -100f // matches PeakRmsMeter.cpp's own kSilenceFloorDb

        init {
            System.loadLibrary("aucampro_native")
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long, preferredInputDeviceId: Int, requestedSampleRateHz: Int): String?
    private external fun nativeGetActualSampleRate(handle: Long): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeReopenInputStream(handle: Long, deviceId: Int): String?
    private external fun nativeInsertSilence(handle: Long, frameCount: Int)
    private external fun nativeSetMonitoringEnabled(handle: Long, enabled: Boolean, outputDeviceId: Int): String?
    private external fun nativeSetEqBandParams(handle: Long, band: Int, freqHz: Float, q: Float, gainDb: Float)
    private external fun nativeSetInputGainDb(handle: Long, gainDb: Float)
    private external fun nativeSetMakeupGainDb(handle: Long, gainDb: Float)
    private external fun nativeSetHighPassEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetHighPassCutoffHz(handle: Long, cutoffHz: Float)
    private external fun nativePeakDb(handle: Long, channel: Int): Float
    private external fun nativeRmsDb(handle: Long, channel: Int): Float
    private external fun nativeRingBufferOverrunCount(handle: Long): Int
    private external fun nativeHardwareXRunCount(handle: Long): Int
    private external fun nativeMonitorWriteShortfallCount(handle: Long): Int
    private external fun nativeGetInputTimestamp(handle: Long): LongArray?
    private external fun nativeDrainEncoderBuffer(handle: Long, dst: FloatArray, maxFrames: Int): Int
    private external fun nativeFlushRingBuffer(handle: Long)
}
