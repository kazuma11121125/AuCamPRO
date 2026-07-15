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
// Audio meter (separate from CameraUiState — see doc below)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Per-channel peak/RMS + xrun/overrun stats, updated at ~30Hz by
 * [CameraControlViewModel.startMeterPolling] for as long as preview is up.
 *
 * Deliberately a *separate* StateFlow ([CameraControlViewModel.meterState]) rather than
 * fields on [CameraUiState]: composables that take the whole `CameraUiState` as a
 * parameter (the control sidebar, its tabs, the REC indicator) only skip recomposition
 * when Compose's structural-equality check on that *whole object* passes — when these
 * fields lived on `CameraUiState`, their ~30Hz churn meant that check failed on every
 * tick, forcing the entire sidebar tree (every slider, both tabs) to fully recompose 30
 * times a second regardless of which tab was open or what the user was actually looking
 * at (confirmed by the user reporting visible preview stutter with no device heat to
 * explain it — a symptom of main-thread contention, not thermal throttling). Isolating
 * this into its own flow means only the few small composables that actually read it
 * (the on-preview meter widget, the AUDIO tab's stats row) recompose on each tick; see
 * [com.procamera.recorder.ui.MainScreen]'s `AudioMeterHost`/`AudioStatsRow` for how they
 * collect this flow locally rather than receiving it as a parameter from further up the
 * tree, which would just reintroduce the same problem one level higher.
 */
data class AudioMeterUiState(
    val peakDbL: Float = -120f,
    val peakDbR: Float = -120f,
    val rmsDbL: Float = -120f,
    val rmsDbR: Float = -120f,
    val isClippingHeldL: Boolean = false,
    val isClippingHeldR: Boolean = false,
    val xrunCount: Int = 0,
    val ringBufferOverrunCount: Int = 0,
)

// ──────────────────────────────────────────────────────────────────────────────
// Control panel tab
// ──────────────────────────────────────────────────────────────────────────────

enum class ControlPanel { Camera, Audio }

// ──────────────────────────────────────────────────────────────────────────────
// Capture mode (photo ⇄ video)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Which action the shutter (hardware camera key + on-screen shutter control) performs.
 * Sony's Photo Pro and Video Pro are two separate apps, each dedicating the same
 * hardware key to one action — this reproduces that split inside a single app rather
 * than showing a still-capture button and a REC indicator side by side at all times
 * (real-device feedback: 静止画と録画はモード切り替えで行う、Photo Proのように).
 */
enum class CaptureMode { Photo, Video }

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

    // ── Audio status (monitoringEnabled only — meter/xrun live in [AudioMeterUiState],
    // a separate StateFlow; see that class's doc for why) ──────────────────────
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

    // ── Capture mode (§CaptureMode's doc) ────────────────────────────────────
    val captureMode: CaptureMode = CaptureMode.Video,
) {
    val isRecording: Boolean get() = recordingState == RecordingUiState.Recording
    val isPreviewing: Boolean get() = recordingState == RecordingUiState.Previewing
    val canStartRecording: Boolean get() = recordingState == RecordingUiState.Previewing
    val canStopRecording: Boolean get() = recordingState == RecordingUiState.Recording
    val recButtonEnabled: Boolean get() =
        recordingState == RecordingUiState.Previewing ||
            recordingState == RecordingUiState.Recording

    /** Mirrors [RecordingPipeline.capturePhoto]'s own guard — PREVIEWING only, NOT while
     * RECORDING (that crashes; see that method's doc for the real-device finding) — kept
     * here too so the on-screen photo button can grey itself out without a round trip. */
    val canCapturePhoto: Boolean get() = isPreviewing

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

    /** mm rather than a "×" multiplier, matching [CameraCapabilityInspector.AvailableLens.
     * zoomLabel]'s doc — the ZOOM slider is continuous *within* the selected lens, so this
     * is that lens's base 35mm-equivalent focal length scaled by the current digital
     * [zoomRatio], not the ratio alone. Falls back to the old "×" form only if the current
     * lens's focal length isn't known yet (e.g. before the first successful [availableLenses]
     * population). */
    val zoomDisplayText: String get() {
        val baseFocalLengthMm = availableLenses.firstOrNull { it.cameraId == selectedLensCameraId }
            ?.equivalentFocalLengthMm
        return if (baseFocalLengthMm != null) {
            "%.0fmm".format(baseFocalLengthMm * zoomRatio)
        } else if (zoomRatio < 1.05f) "1x" else "%.1fx".format(zoomRatio)
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
