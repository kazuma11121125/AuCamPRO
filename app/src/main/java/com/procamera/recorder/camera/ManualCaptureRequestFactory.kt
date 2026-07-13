package com.procamera.recorder.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle

/**
 * Applies §4.1's full-manual-control settings (exposure, WB, FPS-via-frame-duration,
 * touch-to-focus) onto a real `CaptureRequest.Builder`. Pure range-clamping math lives in
 * [CaptureRangeClamper] (unit-tested); this class is the untested framework-facing glue
 * that calls `.set(...)` — Camera2's Builder can only be constructed from a real
 * CameraDevice, so this can't be exercised by a plain JUnit host test (would need
 * Robolectric or an instrumented test, tracked for Phase 5).
 */
class ManualCaptureRequestFactory(
    private val characteristics: CameraCharacteristics,
    val rangeClamper: CaptureRangeClamper = CaptureRangeClamper.fromCharacteristics(characteristics),
) {
    // No single "supported frame duration range" key exists at the sensor level in a
    // stream-independent way (it's per-format/resolution, via
    // StreamConfigurationMap.getOutputMinFrameDuration). §4.1's FPS presets (24-60
    // typical) are comfortably within any FULL-level sensor's capability, so a generous
    // static bound is used here; the actual per-resolution/format ceiling is separately
    // validated by CameraCapabilityInspector.isVideoConfigSupported() before this
    // factory's fps is ever chosen.
    val frameDurationRangeNanos: LongRange = 4_000_000L..1_000_000_000L

    fun applyManualExposure(builder: CaptureRequest.Builder, iso: Int, exposureTimeNanos: Long, fps: Int) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, rangeClamper.clampSensitivity(iso))
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, rangeClamper.clampExposureTimeNanos(exposureTimeNanos))
        // §4.1: FPS is fixed via SENSOR_FRAME_DURATION (the documented correct approach
        // when AE is OFF), not CONTROL_AE_TARGET_FPS_RANGE.
        builder.set(
            CaptureRequest.SENSOR_FRAME_DURATION,
            rangeClamper.frameDurationNanosForFps(fps, frameDurationRangeNanos),
        )
    }

    /** Base state (§4.1): MF locked at a fixed distance, no AF scanning. */
    fun applyManualFocusLocked(builder: CaptureRequest.Builder, focusDistanceDiopters: Float) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, rangeClamper.clampFocusDistance(focusDistanceDiopters))
    }

    /**
     * Temporary state during a tap-to-focus gesture (§4.1's hybrid mechanism): AF_MODE
     * switches to AUTO with a metering region and an explicit trigger. [FocusController]
     * owns switching back to [applyManualFocusLocked] once AF converges.
     */
    fun applyTapToFocusTrigger(builder: CaptureRequest.Builder, region: MeteringRectangle) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
    }

    fun applyManualWhiteBalance(builder: CaptureRequest.Builder, kelvin: Double) {
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, ColorTemperatureConverter.kelvinToRggbGains(kelvin))
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_COLOR_TRANSFORM)
    }

    companion object {
        // 3x3 identity matrix as 9 Rationals (numerator, denominator pairs), row-major —
        // COLOR_CORRECTION_TRANSFORM's required layout. Paired with COLOR_CORRECTION_GAINS
        // doing all the actual WB correction, this transform intentionally does nothing.
        private val IDENTITY_COLOR_TRANSFORM = ColorSpaceTransform(
            intArrayOf(
                1, 1, 0, 1, 0, 1,
                0, 1, 1, 1, 0, 1,
                0, 1, 0, 1, 1, 1,
            ),
        )
    }
}
