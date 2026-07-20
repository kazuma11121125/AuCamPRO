package com.aucampro.recorder.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.util.Log
import android.util.Range

/**
 * Applies §4.1's full-manual-control settings (exposure, WB, FPS-via-frame-duration,
 * touch-to-focus) onto a real `CaptureRequest.Builder`. Pure range-clamping math lives in
 * [CaptureRangeClamper] (unit-tested); this class is the untested framework-facing glue
 * that calls `.set(...)` — Camera2's Builder can only be constructed from a real
 * CameraDevice, so this can't be exercised by a plain JUnit host test.
 *
 * Phase 4 UI additions:
 * - [applyWhiteBalance]: replaced the old `applyManualWhiteBalance(kelvin)` with a unified
 *   method that switches between Auto AWB and manual Kelvin depending on [CameraParams.wbAuto].
 *   デフォルトはオートWB — Kelvin→RGGBゲインはセンサー固有の分光感度に依存するため
 *   デバイス非依存の近似変換は必ずズレが生じる。オートを選べば ISP が適切に補正する。
 * - [applyZoom]: デジタルズームを SCALER_CROP_REGION で実装(API 29 互換)。
 */
class ManualCaptureRequestFactory(
    private val characteristics: CameraCharacteristics,
    val rangeClamper: CaptureRangeClamper = CaptureRangeClamper.fromCharacteristics(characteristics),
) {
    // Active array size — needed to compute the crop region for digital zoom.
    private val activeArray: Rect? =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

    /** Max digital zoom supported (1.0 if unknown). */
    val maxDigitalZoom: Float =
        characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

    val frameDurationRangeNanos: LongRange = 4_000_000L..1_000_000_000L

    fun applyManualExposure(builder: CaptureRequest.Builder, iso: Int, exposureTimeNanos: Long, fps: Int) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, rangeClamper.clampSensitivity(iso))
        // 二重防御(docs/VIDEO_FPS_STUTTER_INVESTIGATION_2026-07-20.md §2): ViewModel側で
        // fps変更時にシャッタースピードをクランプしているが、CaptureRequestを実際に組み立てる
        // ここでも同じクランプを通す — 呼び出し元の経路が増えても
        // frameDuration>=exposureTimeが常に成立することを保証する。
        val clampedExposureTimeNanos = CaptureRangeClamper.clampExposureTimeNanosToFrameRate(exposureTimeNanos, fps)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, rangeClamper.clampExposureTimeNanos(clampedExposureTimeNanos))
        builder.set(
            CaptureRequest.SENSOR_FRAME_DURATION,
            rangeClamper.frameDurationNanosForFps(fps, frameDurationRangeNanos),
        )
    }

    /**
     * `ExposureMode.AUTO`: `CONTROL_AE_MODE_ON` + a `CONTROL_AE_TARGET_FPS_RANGE` picked
     * from the device's actually-advertised ranges (never an unconditional `Range(fps,
     * fps)` — see [CaptureRangeClamper.selectAeFpsRange]'s doc). Does **not** set
     * `SENSOR_SENSITIVITY`/`SENSOR_EXPOSURE_TIME`/`SENSOR_FRAME_DURATION` — those are
     * `AE_MODE_OFF`-only keys and setting them alongside `AE_MODE_ON` would fight the ISP's
     * own exposure control. No stale-value risk from a previous `applyManualExposure` call:
     * [CameraSessionController.buildRequestBuilder] always starts from a fresh
     * `createCaptureRequest(TEMPLATE_RECORD)` builder, never reuses one.
     *
     * [lock] is a real-device-comparison test hook (2026-07-20 investigation §4.3,
     * AE_ON vs AE_ON+AE_LOCK vs AE_OFF under matched `CAM_CRITICAL` conditions) — always
     * `false` from the normal `ExposureMode.AUTO` product path; only
     * [CameraParams.debugAeLock] threads a `true` here, and only after real-device
     * verification decides it's worth exposing as an actual mode.
     */
    fun applyAutoExposure(builder: CaptureRequest.Builder, targetFps: Int, lock: Boolean = false) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, resolveAeFpsRange(targetFps))
        builder.set(CaptureRequest.CONTROL_AE_LOCK, lock)
    }

    private fun resolveAeFpsRange(targetFps: Int): Range<Int> {
        val available = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower to it.upper }
            ?: emptyList()
        val selected = CaptureRangeClamper.selectAeFpsRange(available, targetFps)
        Log.i(
            "ManualCaptureRequestFactory",
            "AE fps range: target=$targetFps available=$available selected=$selected",
        )
        // available being empty/not containing targetFps is unexpected (the fps picker
        // should already only offer fps the encoder+device claim to support) but not a
        // reason to crash a recording — fall back to the naive fixed range rather than
        // erroring, same "graceful degradation over a hard failure" style as the
        // histogram/photo-reader fallbacks elsewhere in this codebase.
        val (lower, upper) = selected ?: (targetFps to targetFps)
        return Range(lower, upper)
    }

    /** Base state (§4.1): MF locked at a fixed distance or continuous video AF. */
    fun applyFocus(builder: CaptureRequest.Builder, focusDistanceDiopters: Float, afAuto: Boolean) {
        if (afAuto) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, rangeClamper.clampFocusDistance(focusDistanceDiopters))
        }
    }

    /**
     * Temporary state during a tap-to-focus gesture (§4.1's hybrid mechanism).
     */
    fun applyTapToFocusTrigger(builder: CaptureRequest.Builder, region: MeteringRectangle) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
    }

    /**
     * White balance — Auto or Manual Kelvin.
     *
     * **Auto mode** ([wbAuto]=true, デフォルト):
     *   Sets `CONTROL_AWB_MODE_CONTINUOUS_AUTO_PICTURE`. The ISP handles colour correction
     *   using its sensor-specific calibration data. This is always more accurate than our
     *   Tanner Helland approximation on a device we haven't calibrated against.
     *
     * **Manual mode** ([wbAuto]=false):
     *   Sets `CONTROL_AWB_MODE_OFF` and applies Kelvin→RGGB via [ColorTemperatureConverter].
     *   Note: the gains are an approximation. Accuracy varies by sensor.
     *   `supportsManualWb` gate must be checked before exposing this in the UI (Sony SO-51C
     *   supports MANUAL_POST_PROCESSING per [CameraCapabilityInspector.supportsManualWhiteBalance]).
     */
    fun applyWhiteBalance(builder: CaptureRequest.Builder, params: CameraParams) {
        if (params.wbAuto) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            return
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        val gains = params.manualWbGains ?: ColorTemperatureConverter.kelvinToRggbGains(params.kelvin)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_COLOR_TRANSFORM)
    }

    /**
     * Digital zoom via `SCALER_CROP_REGION` (API 29 compatible).
     *
     * Crops the centre of the active array to `1/zoomRatio` of its original area.
     * The aspect ratio of the active array is preserved automatically by dividing both
     * width and height by the same factor.
     *
     * Camera2 HAL scales the cropped region back to the full output resolution, giving the
     * appearance of zoom. Quality degrades beyond [maxDigitalZoom] (ISP upscale limit).
     *
     * No-op when [zoomRatio] ≤ 1.0 (clearing the crop region restores 1:1 mapping).
     */
    fun applyZoom(builder: CaptureRequest.Builder, zoomRatio: Float) {
        val array = activeArray ?: return
        val clamped = zoomRatio.coerceIn(1f, maxDigitalZoom)
        if (clamped <= 1.0f) {
            // Restore full sensor area — explicitly clear any previous crop.
            builder.set(CaptureRequest.SCALER_CROP_REGION, array)
            return
        }
        val cropWidth = (array.width() / clamped).toInt()
        val cropHeight = (array.height() / clamped).toInt()
        val left = array.left + (array.width() - cropWidth) / 2
        val top = array.top + (array.height() - cropHeight) / 2
        builder.set(
            CaptureRequest.SCALER_CROP_REGION,
            Rect(left, top, left + cropWidth, top + cropHeight),
        )
    }

    // Keep the old method name as a bridge (called from CameraSessionController.buildRequest
    // which was written before the wbAuto param was added — updated in this commit).
    @Deprecated("Use applyWhiteBalance(builder, params)", ReplaceWith("applyWhiteBalance(builder, CameraParams(kelvin = kelvin, wbAuto = false))"))
    fun applyManualWhiteBalance(builder: CaptureRequest.Builder, kelvin: Double) {
        applyWhiteBalance(builder, CameraParams(kelvin = kelvin, wbAuto = false))
    }

    companion object {
        private val IDENTITY_COLOR_TRANSFORM = ColorSpaceTransform(
            intArrayOf(
                1, 1, 0, 1, 0, 1,
                0, 1, 1, 1, 0, 1,
                0, 1, 0, 1, 1, 1,
            ),
        )
    }
}
