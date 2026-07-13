package com.procamera.recorder.audio

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
    private var closed = false

    /** [preferredInputDeviceId] of 0 means "let the OS choose" (oboe::kUnspecified). */
    fun start(preferredInputDeviceId: Int = kUnspecifiedDeviceId): String? =
        nativeStart(handle, preferredInputDeviceId)

    fun stop() = nativeStop(handle)

    fun reopenInputStream(deviceId: Int): String? = nativeReopenInputStream(handle, deviceId)

    fun insertSilence(frameCount: Int) = nativeInsertSilence(handle, frameCount)

    fun setMonitoringEnabled(enabled: Boolean, outputDeviceId: Int = kUnspecifiedDeviceId): String? =
        nativeSetMonitoringEnabled(handle, enabled, outputDeviceId)

    fun setEqBandParams(band: Int, freqHz: Float, q: Float, gainDb: Float) =
        nativeSetEqBandParams(handle, band, freqHz, q, gainDb)

    fun peakDb(): Float = nativePeakDb(handle)

    fun rmsDb(): Float = nativeRmsDb(handle)

    fun ringBufferOverrunCount(): Int = nativeRingBufferOverrunCount(handle)

    fun hardwareXRunCount(): Int = nativeHardwareXRunCount(handle)

    /**
     * One-shot (framePosition, timeNanos) correlation at CLOCK_MONOTONIC, used to seed
     * [com.procamera.recorder.muxer.PtsClockDomain]'s audio anchor with the audio
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

    /** Drains up to [maxFrames] stereo frames into [dst] (must be sized >= maxFrames*2). */
    fun drainEncoderBuffer(dst: FloatArray, maxFrames: Int): Int = nativeDrainEncoderBuffer(handle, dst, maxFrames)

    override fun close() {
        if (!closed) {
            nativeStop(handle)
            nativeDestroy(handle)
            closed = true
        }
    }

    private companion object {
        const val kUnspecifiedDeviceId = 0 // matches oboe::kUnspecified

        init {
            System.loadLibrary("procamera_native")
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long, preferredInputDeviceId: Int): String?
    private external fun nativeStop(handle: Long)
    private external fun nativeReopenInputStream(handle: Long, deviceId: Int): String?
    private external fun nativeInsertSilence(handle: Long, frameCount: Int)
    private external fun nativeSetMonitoringEnabled(handle: Long, enabled: Boolean, outputDeviceId: Int): String?
    private external fun nativeSetEqBandParams(handle: Long, band: Int, freqHz: Float, q: Float, gainDb: Float)
    private external fun nativePeakDb(handle: Long): Float
    private external fun nativeRmsDb(handle: Long): Float
    private external fun nativeRingBufferOverrunCount(handle: Long): Int
    private external fun nativeHardwareXRunCount(handle: Long): Int
    private external fun nativeGetInputTimestamp(handle: Long): LongArray?
    private external fun nativeDrainEncoderBuffer(handle: Long, dst: FloatArray, maxFrames: Int): Int
}
