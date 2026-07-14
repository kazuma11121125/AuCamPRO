package com.procamera.recorder.ui.viewmodel

import android.net.Uri
import com.procamera.recorder.camera.CameraCapabilityInspector
import com.procamera.recorder.camera.CaptureRangeClamper
import com.procamera.recorder.pipeline.RecordingPipeline
import java.io.File

// ──────────────────────────────────────────────────────────────────────────────
// Recording state
// ──────────────────────────────────────────────────────────────────────────────

enum class RecordingUiState {
    Idle,
    StartingPreview,
    Previewing,
    StartingRecording,
    Recording,
    Stopping,
}

// ──────────────────────────────────────────────────────────────────────────────
// EQ band
// ──────────────────────────────────────────────────────────────────────────────

data class EqBandState(
    val label: String,
    val freqHz: Float,
    val q: Float,
    val gainDb: Float,
    val freqRange: ClosedFloatingPointRange<Float>,
    val qRange: ClosedFloatingPointRange<Float>,
    val gainRange: ClosedFloatingPointRange<Float>,
) {
    companion object {
        fun defaults(): List<EqBandState> = listOf(
            EqBandState("Low", 80f, 0.8f, -6f, 20f..500f, 0.1f..4f, -12f..12f),
            EqBandState("Mid", 1500f, 1.2f, 3f, 200f..8000f, 0.1f..4f, -12f..12f),
            EqBandState("High", 8000f, 0.7f, -4f, 2000f..20000f, 0.1f..4f, -12f..12f),
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Control panel tab
// ──────────────────────────────────────────────────────────────────────────────

enum class ControlPanel { Camera, Audio }

// ──────────────────────────────────────────────────────────────────────────────
// Storage location type
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Where finished recordings are written. The ViewModel resolves the actual File/Uri from this.
 *
 * - [AppPrivate]: `Context.getExternalFilesDir("recordings")` — always accessible, not visible
 *   in Gallery, auto-deleted on app uninstall.
 * - [PublicMovies]: `Environment.DIRECTORY_MOVIES` via MediaStore — shows in Gallery,
 *   persists after uninstall.
 * - [Custom]: User-picked directory URI via SAF (ACTION_OPEN_DOCUMENT_TREE). Requires a
 *   persisted `takePersistableUriPermission`.
 */
sealed interface StorageLocation {
    data object AppPrivate : StorageLocation
    data object PublicMovies : StorageLocation
    data class Custom(val uri: Uri, val displayPath: String) : StorageLocation
}

// ──────────────────────────────────────────────────────────────────────────────
// Frame-line composition guide
// ──────────────────────────────────────────────────────────────────────────────

/**
 * A composition guide overlaid on the preview — a bordered rectangle at [ratio] (width /
 * height) with the area outside it dimmed, matching the "Frame lines" feature surveyed
 * from Sony's Photography/Cinematography Pro apps (Sony PDF調査: "実際に記録される映像の
 * アスペクト比とは独立しており、ポストプロダクションでのクロップを想定した構図決定に用いる").
 *
 * **確信度の明示**: this is deliberately *not* an encode-time crop — the recorded file's
 * aspect ratio is unaffected; only the on-screen preview gets the guide overlay. A true
 * encode-time crop to an arbitrary ratio (progress_summary.md's original "自由なアスペクト
 * 比のクロップ設定" request) would require inserting a GL rendering stage between Camera2
 * and the encoder's InputSurface — see [com.procamera.recorder.encoder.VideoEncoder]'s doc
 * for why the current direct camera→encoder path's PTS behavior was hard-won on real
 * hardware, and why that GL insertion was deferred rather than attempted blind.
 */
enum class FrameLineAspectRatio(val ratio: Float?, val label: String) {
    Off(null, "オフ"),
    Square(1f, "1:1"),
    Classic(4f / 3f, "4:3"),
    Portrait(9f / 16f, "9:16"),
}

// ──────────────────────────────────────────────────────────────────────────────
// Settings state (shown in SettingsBottomSheet)
// ──────────────────────────────────────────────────────────────────────────────

data class SettingsState(
    val storageLocation: StorageLocation = StorageLocation.AppPrivate,
    val segmentDurationMinutes: Int = 5,  // 1, 5, 10, 15, 30
    val frameLineAspectRatio: FrameLineAspectRatio = FrameLineAspectRatio.Off,
    val showSettingsSheet: Boolean = false,
)

// ──────────────────────────────────────────────────────────────────────────────
// Main UI state — single source of truth for MainScreen
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Complete UI state for [com.procamera.recorder.ui.MainScreen].
 */
data class CameraUiState(

    // ── Recording state ──────────────────────────────────────────────────────
    val recordingState: RecordingUiState = RecordingUiState.Idle,
    val recordingElapsedMs: Long = 0L,
    val currentSegment: Int = 0,
    val outputDirectory: File? = null,

    // ── Camera capabilities (filled after first startPreview succeeds) ────────
    val capabilities: RecordingPipeline.CameraCapabilities? = null,

    // ── Available lenses + selected lens ────────────────────────────────────
    /**
     * All rear-facing lenses discovered by [CameraCapabilityInspector.allRearLenses].
     * Populated once on first [startPreview].
     */
    val availableLenses: List<CameraCapabilityInspector.AvailableLens> = emptyList(),
    val selectedLensCameraId: String? = null,

    // ── Available video configs + selected ───────────────────────────────────
    /**
     * All video configs the current lens + device supports.
     * Populated/updated when the selected lens changes.
     */
    val availableVideoConfigs: List<CameraCapabilityInspector.VideoConfigCandidate> = emptyList(),
    val selectedVideoConfig: CameraCapabilityInspector.VideoConfigCandidate? = null,

    // ── Camera manual params ─────────────────────────────────────────────────
    val iso: Int = 400,
    val exposureTimeNanos: Long = 1_000_000_000L / 60,
    val shutterPreset: CaptureRangeClamper.ShutterPreset? = CaptureRangeClamper.ShutterPreset.S_1_60,
    val focusDistanceDiopters: Float = 0f,
    val kelvin: Double = 5_500.0,
    val fps: Int = 30,

    // ── White balance & Focus ────────────────────────────────────────────────
    /**
     * When true: camera ISP handles AWB automatically (デフォルト推奨).
     * When false: manual Kelvin via [kelvin].
     */
    val wbAuto: Boolean = true,
    
    /**
     * When true: Continuous video autofocus.
     * When false: Locked manual focus.
     */
    val afAuto: Boolean = true,
    
    val manualWbGains: android.hardware.camera2.params.RggbChannelVector? = null,

    // ── Digital zoom ─────────────────────────────────────────────────────────
    /**
     * Current digital zoom (1.0 = no zoom). Upper bound is the current lens's
     * [CameraCapabilityInspector.AvailableLens.maxDigitalZoom].
     */
    val zoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 1.0f,

    // ── Audio meter (independent per channel — see dsp/PeakRmsMeter.h) ─────────
    val peakDbL: Float = -120f,
    val peakDbR: Float = -120f,
    val rmsDbL: Float = -120f,
    val rmsDbR: Float = -120f,
    val isClippingHeldL: Boolean = false,
    val isClippingHeldR: Boolean = false,

    // ── Audio status ─────────────────────────────────────────────────────────
    val xrunCount: Int = 0,
    val ringBufferOverrunCount: Int = 0,
    val monitoringEnabled: Boolean = false,

    // ── Input gain (record level) ───────────────────────────────────────────
    // Post-ADC digital gain, applied before EQ/limiter — see dsp/InputGain.h. Range is
    // asymmetric ([-24, +12]dB, see the slider) toward attenuation: this app's target
    // scenario is a loud live band (110-125dB SPL), where the built-in mic's fixed analog
    // gain (tuned for ordinary use) is far more likely to need pulling down than boosting.
    val inputGainDb: Float = 0f,

    // ── EQ ───────────────────────────────────────────────────────────────────
    val eqBands: List<EqBandState> = EqBandState.defaults(),

    // ── Storage / performance ─────────────────────────────────────────────────
    val storageRemainingSeconds: Long = Long.MAX_VALUE,

    // ── Control panel ────────────────────────────────────────────────────────
    val activePanel: ControlPanel = ControlPanel.Camera,

    // ── Settings ─────────────────────────────────────────────────────────────
    val settings: SettingsState = SettingsState(),

    // ── Error / informational banner ─────────────────────────────────────────
    val errorMessage: String? = null,

    // ── Thermal (§4.6) ───────────────────────────────────────────────────────
    // android.os.PowerManager.THERMAL_STATUS_* — kept as a raw Int (rather than importing
    // the framework type into this otherwise-framework-free state class) since the only
    // consumer is a >= THERMAL_STATUS_SEVERE comparison in the UI layer.
    val thermalStatus: Int = 0, // PowerManager.THERMAL_STATUS_NONE

    // ── UI Visibility ────────────────────────────────────────────────────────
    val showControls: Boolean = true,
) {
    val isRecording: Boolean get() = recordingState == RecordingUiState.Recording
    val isPreviewing: Boolean get() = recordingState == RecordingUiState.Previewing
    val canStartRecording: Boolean get() = recordingState == RecordingUiState.Previewing
    val canStopRecording: Boolean get() = recordingState == RecordingUiState.Recording
    val recButtonEnabled: Boolean get() =
        recordingState == RecordingUiState.Previewing ||
            recordingState == RecordingUiState.Recording

    // THERMAL_STATUS_SEVERE == 3 (android.os.PowerManager) — see thermalStatus's doc for
    // why this is a raw Int comparison rather than a framework-typed field.
    val isThermalWarning: Boolean get() = thermalStatus >= 3

    val elapsedFormatted: String get() {
        val totalSec = recordingElapsedMs / 1_000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    val storageRemainingFormatted: String get() {
        if (storageRemainingSeconds == Long.MAX_VALUE) return "–"
        val m = storageRemainingSeconds / 60
        val h = m / 60
        return if (h > 0) "${h}h${m % 60}m" else "${m}m"
    }

    val shutterDisplayText: String get() {
        val preset = shutterPreset
        if (preset != null) return "1/${preset.fractionDenominator}"
        val denom = (1_000_000_000.0 / exposureTimeNanos).toLong()
        return "1/$denom"
    }

    val kelvinDisplayText: String get() = "${kelvin.toInt()}K"

    val inputGainDisplayText: String get() {
        val sign = if (inputGainDb > 0f) "+" else ""
        return "$sign${"%.1f".format(inputGainDb)}dB"
    }

    val focusDisplayText: String get() {
        if (focusDistanceDiopters == 0f) return "∞"
        val distanceMeters = 1f / focusDistanceDiopters
        return if (distanceMeters >= 10f) "%.0fm".format(distanceMeters)
        else "%.1fm".format(distanceMeters)
    }

    val zoomDisplayText: String get() {
        return if (zoomRatio < 1.05f) "1x" else "%.1fx".format(zoomRatio)
    }

    val videoConfigDisplayText: String get() {
        val cfg = selectedVideoConfig ?: return "—"
        val codec = if (cfg.mimeType.contains("hevc", ignoreCase = true)) "HEVC" else "H.264"
        val res = if (cfg.width >= 3840) "4K" else if (cfg.width >= 1920) "1080p" else "720p"
        val mbps = cfg.bitrate / 1_000_000
        return "$res ${cfg.frameRate}fps $codec ${mbps}Mbps"
    }

    val wbDisplayText: String get() = if (wbAuto) "AWB" else "${kelvin.toInt()}K"
}
