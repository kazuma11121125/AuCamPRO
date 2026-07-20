package com.aucampro.recorder.camera

import android.hardware.camera2.CameraCharacteristics

/**
 * Pure clamping/conversion logic for manual exposure controls (§4.1), factored out of
 * [ManualCaptureRequestFactory] so it's unit-testable without a real/mocked
 * CameraCharacteristics or CaptureRequest.Builder (both framework classes plain JUnit
 * host tests can't construct without Robolectric).
 *
 * Uses Kotlin's own IntRange/LongRange rather than android.util.Range: verified
 * empirically that android.util.Range's accessors throw ("not mocked") under plain JUnit
 * host tests even though the class itself constructs fine — only Kotlin/JVM stdlib types
 * are usable here without Robolectric.
 */
class CaptureRangeClamper(
    val sensitivityRange: IntRange,
    val exposureTimeRangeNanos: LongRange,
    val minFocusDistanceDiopters: Float,
) {
    /** LED PWM flicker-avoidance shutter presets (§4.1). */
    enum class ShutterPreset(val fractionDenominator: Int) {
        S_1_50(50),
        S_1_60(60),
        S_1_100(100),
        S_1_120(120),
        ;

        fun exposureTimeNanos(): Long = 1_000_000_000L / fractionDenominator
    }

    fun clampSensitivity(iso: Int): Int = iso.coerceIn(sensitivityRange)

    fun clampExposureTimeNanos(nanos: Long): Long = nanos.coerceIn(exposureTimeRangeNanos)

    /**
     * LENS_FOCUS_DISTANCE is in diopters (0 = infinity, [minFocusDistanceDiopters] =
     * closest focus); this clamps into that device-specific valid range.
     */
    fun clampFocusDistance(diopters: Float): Float = diopters.coerceIn(0f, minFocusDistanceDiopters)

    /**
     * SENSOR_FRAME_DURATION for a target frame rate, clamped to the sensor's supported
     * frame-duration range so an out-of-range FPS request doesn't silently produce an
     * invalid capture request. (§4.1: FPS is fixed via SENSOR_FRAME_DURATION, not
     * CONTROL_AE_TARGET_FPS_RANGE, when AE is OFF.)
     */
    fun frameDurationNanosForFps(fps: Int, frameDurationRangeNanos: LongRange): Long {
        val ideal = 1_000_000_000L / fps
        return ideal.coerceIn(frameDurationRangeNanos)
    }

    companion object {
        /**
         * Camera2 always enforces `SENSOR_FRAME_DURATION >= SENSOR_EXPOSURE_TIME` — a
         * shutter speed left over from a slower video fps selection silently caps the
         * *actual* frame rate below whatever fps was just picked, independent of any
         * per-device sensitivity/exposure range (so this needs no [CaptureRangeClamper]
         * instance). Real-device-confirmed 2026-07-20: a persisted ~1/33s shutter speed
         * forced every recording to ~33fps regardless of the selected 30/60fps target —
         * see docs/VIDEO_FPS_STUTTER_INVESTIGATION_2026-07-20.md §2. Leaves
         * [exposureTimeNanos] unchanged if it already fits [frameRate]'s frame period (a
         * *faster* shutter than the frame period is always fine).
         */
        fun clampExposureTimeNanosToFrameRate(exposureTimeNanos: Long, frameRate: Int): Long {
            val frameDurationNanos = 1_000_000_000L / frameRate
            return minOf(exposureTimeNanos, frameDurationNanos)
        }

        /**
         * Picks the best `CONTROL_AE_TARGET_FPS_RANGE` for [targetFps] from
         * [availableRanges] (`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`, as `lower to upper`
         * pairs — plain `Pair<Int, Int>` rather than `android.util.Range` for the same
         * host-testability reason as this class's other methods, see class doc). Never
         * builds `Range(targetFps, targetFps)` unconditionally — that range might not
         * actually be one of the device's advertised options.
         *
         * Priority: (1) an exact `[targetFps, targetFps]` fixed range, (2) the narrowest
         * range that contains [targetFps] (least "slack" for the HAL to pick some other
         * rate within it), (3) tie-broken by the higher lower bound (biases toward ranges
         * that can't drop as low), (4) `null` if [targetFps] isn't in any available range
         * — caller decides the fallback (this class doesn't guess one).
         */
        fun selectAeFpsRange(availableRanges: List<Pair<Int, Int>>, targetFps: Int): Pair<Int, Int>? {
            val exact = availableRanges.firstOrNull { it.first == targetFps && it.second == targetFps }
            if (exact != null) return exact

            val containing = availableRanges.filter { targetFps in it.first..it.second }
            if (containing.isEmpty()) return null

            return containing.sortedWith(compareBy({ it.second - it.first }, { -it.first })).first()
        }

        fun fromCharacteristics(characteristics: CameraCharacteristics): CaptureRangeClamper {
            val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            return CaptureRangeClamper(
                sensitivityRange = if (sensitivityRange != null) {
                    sensitivityRange.lower..sensitivityRange.upper
                } else {
                    100..800
                },
                exposureTimeRangeNanos = if (exposureTimeRange != null) {
                    exposureTimeRange.lower..exposureTimeRange.upper
                } else {
                    1_000_000L..1_000_000_000L
                },
                minFocusDistanceDiopters = minFocusDistance,
            )
        }
    }
}
