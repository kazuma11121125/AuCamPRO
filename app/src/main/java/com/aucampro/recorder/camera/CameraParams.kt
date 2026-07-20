package com.aucampro.recorder.camera

import android.graphics.Rect

/**
 * Snapshot of all camera manual-control settings (§4.1) — expanded for Phase 4 UI:
 *
 * - [wbAuto]: `true` = CONTROL_AWB_MODE_CONTINUOUS_AUTO_PICTURE (デフォルト).
 *   `false` = CONTROL_AWB_MODE_OFF + COLOR_CORRECTION_GAINS from [kelvin].
 *   WBの色味問題の根本解決策: オートWBをデフォルトに変更し、手動Kelvinを補助として残す。
 *   理由: Camera2の RGGB GAINS はセンサー固有の分光感度に依存するため、デバイスを跨いだ
 *   汎用Kelvin→RGGB変換はどうしても近似値に留まる。オートWBを選択すれば ISP が
 *   適切な値を計算するため、色味問題が回避できる。
 *
 * - [zoomRatio]: デジタルズーム倍率 (1.0 = ズームなし). [CameraSessionController] が
 *   `SCALER_CROP_REGION` として Camera2 に渡す。最大値は機種ごとの
 *   `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` に制約される。
 */
data class CameraParams(
    /** ISO sensitivity — clamped to [CaptureRangeClamper.sensitivityRange]. */
    val iso: Int = 400,
    /**
     * Exposure time in nanoseconds — clamped to [CaptureRangeClamper.exposureTimeRangeNanos].
     */
    val exposureTimeNanos: Long = 1_000_000_000L / 60, // default 1/60s
    /**
     * Manual focus distance in diopters (0 = infinity; max = closest focus point).
     */
    val focusDistanceDiopters: Float = 0f,
    /** White balance colour temperature in Kelvin (2500–8000 K). Used only when [wbAuto]=false. */
    val kelvin: Double = 5_500.0,
    /**
     * When true: let the ISP run CONTINUOUS_AUTO_PICTURE AWB (推奨デフォルト).
     * When false: apply [kelvin] via COLOR_CORRECTION_GAINS (手動モード).
     */
    val wbAuto: Boolean = true,
    /**
     * When true: use CONTROL_AF_MODE_CONTINUOUS_VIDEO for continuous auto-focus.
     * When false: lock focus to [focusDistanceDiopters].
     */
    val afAuto: Boolean = true,
    /**
     * Target frame rate (used to set SENSOR_FRAME_DURATION).
     */
    val fps: Int = 30,
    /**
     * Digital zoom ratio (1.0 = no crop, max = device's SCALER_AVAILABLE_MAX_DIGITAL_ZOOM).
     * Implemented via SCALER_CROP_REGION (works on API 29+; API 30+ could use
     * CONTROL_ZOOM_RATIO but SCALER_CROP_REGION has better backward compatibility).
     */
    val zoomRatio: Float = 1.0f,
    /**
     * Exact RGB gains captured from Auto mode to ensure seamless transition to Manual mode.
     */
    val manualWbGains: android.hardware.camera2.params.RggbChannelVector? = null,
    /** [ExposureMode.MANUAL] = existing default (ISO/shutter fully manual, AE_MODE_OFF). */
    val exposureMode: ExposureMode = ExposureMode.MANUAL,
    /**
     * 実機比較実験専用(2026-07-20調査): [exposureMode]が[ExposureMode.AUTO]の時だけ意味を
     * 持つ。`true`で`CONTROL_AE_LOCK`を立てる — AE収束後にロックした状態が、高温時のHAL内部
     * フレーム損失についてAE_ON(ロックなし)/AE_OFFとどう違うかを比較するための一時フラグ。
     * 製品UIからは到達できない(通常のExposureMode切替では常にfalse) — 実機比較時は一時的な
     * コード変更で有効にする。docs/VIDEO_FPS_STUTTER_INVESTIGATION_2026-07-20.md §4.3参照。
     */
    val debugAeLock: Boolean = false,
)
