package com.procamera.recorder.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.procamera.recorder.camera.CameraCapabilityInspector
import com.procamera.recorder.camera.CameraParams
import com.procamera.recorder.camera.CaptureRangeClamper
import com.procamera.recorder.pipeline.RecordingPipeline
import com.procamera.recorder.utils.ThermalMonitor
import com.procamera.recorder.utils.UserPreferencesStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel for [com.procamera.recorder.ui.MainScreen].
 *
 * Phase 4 UI additions vs the original (smoke-test) version:
 * - [switchLens]: stops current preview session and restarts with a different camera.
 * - [selectVideoConfig]: sets the video config to use for the next recording.
 * - [setZoom]: live SCALER_CROP_REGION update via [RecordingPipeline.updateCameraParams].
 * - [setWbAuto] / [setKelvin]: toggle Auto AWB vs manual Kelvin.
 * - [openSettings] / [closeSettings]: drive [SettingsState.showSettingsSheet].
 * - [setStorageLocation]: persist SAF URI or standard location choice.
 * - [setSegmentDuration]: configures segment duration forwarded to [RecordingPipeline].
 */
class CameraControlViewModel(app: Application) : AndroidViewModel(app) {

    private val pipeline = RecordingPipeline(app)
    private val capabilityInspector =
        CameraCapabilityInspector(app.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager)
    private val thermalMonitor = ThermalMonitor(app)
    private val userPrefs = UserPreferencesStore(app)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // Separate from [_uiState] — see [AudioMeterUiState]'s doc for why the ~30Hz meter
    // churn must not live on the same object the sidebar/tabs recompose against.
    private val _meterState = MutableStateFlow(AudioMeterUiState())
    val meterState: StateFlow<AudioMeterUiState> = _meterState.asStateFlow()

    // Same reasoning as [_meterState] — histogram samples arrive at close to preview
    // frame rate, so this must not live on [_uiState] either.
    private val _histogramBins = MutableStateFlow<FloatArray?>(null)
    val histogramBins: StateFlow<FloatArray?> = _histogramBins.asStateFlow()

    private var lastAutoKelvin: Double? = null
    private var lastAutoGains: android.hardware.camera2.params.RggbChannelVector? = null
    private var lastAutoFocus: Float? = null

    /** Guards [restorePersistedSettings] to run only once per process — attachPreviewSurface
     * re-enters on every screen-lock/unlock cycle (§4.6), which must NOT re-apply the saved
     * settings over whatever the user has since changed live. */
    private var hasRestoredPersistedSettings = false

    init {
        // Debounced auto-save: writes settle ~800ms after the last *persistence-relevant*
        // change, not on every intermediate value during a slider drag
        // (UserPreferencesStore.save's doc). Maps down to a small equality-comparable
        // snapshot BEFORE debounce()/distinctUntilChanged() rather than mapping straight
        // off the raw `uiState` flow — `uiState` no longer carries the audio meter's
        // ~30Hz churn (see [AudioMeterUiState]'s doc for why that now lives in its own
        // flow), but this explicit allowlist is still worth keeping: it's a single place
        // that documents exactly which fields are meant to survive a restart, independent
        // of whatever telemetry/derived fields `CameraUiState` grows next (real-hardware
        // history: this collector previously went silent entirely — see git history for
        // the meter-churn incident this snapshot was originally built to fix — when a
        // frequently-changing field lived on the raw flow and debounce()'s quiet-gap
        // requirement was never met).
        // drop(1): skip the initial CameraUiState() default snapshot — nothing to persist
        // yet, and it would otherwise briefly overwrite a real saved value with the default
        // if this collector's first tick raced restorePersistedSettings's own writes.
        viewModelScope.launch {
            uiState
                .map { PersistSnapshot(it) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(800L)
                .collect { userPrefs.save(_uiState.value) }
        }

        // Registers this ViewModel's single RecordingPipeline instance with the
        // Application-level crash handler so an uncaught exception can attempt a
        // best-effort finalize of whatever segment is currently open (§4.6) — see
        // ProCameraApplication and RecordingPipeline.emergencyFinalizeCurrentSegment's docs.
        (app as? com.procamera.recorder.ProCameraApplication)?.activeRecordingPipeline = pipeline

        // §4.6: monitor for the whole time this screen is up (not just while recording —
        // extended preview during setup/soundcheck can heat the device too), surfacing a
        // warning banner at THERMAL_STATUS_SEVERE+. Recording quality itself is never
        // auto-changed (per spec — that decision is left to the user); see ThermalMonitor's
        // doc for what's implemented vs deferred (preview resolution/fps step-down).
        thermalMonitor.start { status ->
            _uiState.update { it.copy(thermalStatus = status) }
        }

        pipeline.onAutoWbGainsMeasured = { gains, kelvin ->
            lastAutoKelvin = kelvin
            lastAutoGains = gains
            if (_uiState.value.wbAuto) {
                if (kotlin.math.abs(_uiState.value.kelvin - kelvin) > 50.0) {
                    _uiState.update { it.copy(kelvin = kelvin) }
                }
            }
        }
        pipeline.onHistogramUpdated = { bins ->
            _histogramBins.value = bins
        }
        pipeline.onAutoFocusMeasured = { focus ->
            lastAutoFocus = focus
            if (_uiState.value.afAuto) {
                if (kotlin.math.abs(_uiState.value.focusDistanceDiopters - focus) > 0.1f) {
                    _uiState.update { it.copy(focusDistanceDiopters = focus) }
                }
            }
        }
        // Long-press-to-focus (§4.1) converged/timed-out — switch the UI to MF at the
        // resulting distance, mirroring what FocusController already locked the live
        // capture session to (see RequestSubmitter's doc for why that lock already took
        // effect before this callback fires — this call is UI-display sync, not a second
        // camera-facing write, though re-deriving CameraParams from the just-updated state
        // and pushing it is cheap/idempotent so it's included for consistency with
        // setFocusDistance/setAfAuto's own pattern).
        pipeline.onTapToFocusLocked = { distance ->
            _uiState.update { it.copy(focusDistanceDiopters = distance, afAuto = false) }
            pipeline.updateCameraParams(_uiState.value.toCameraParams())
        }
        pipeline.onMediaCaptured = { uri, isVideo ->
            _uiState.update { it.copy(lastCapturedUri = uri, lastCapturedIsVideo = isVideo) }
        }
        // §フォーカス位置表示: shows immediately at Scanning, then auto-hides a couple
        // seconds after the scan resolves (Locked/Failed) — a real camera's focus
        // reticle doesn't stay on screen forever once it's told you what you need to
        // know. Re-tapping while the previous reticle is still fading cancels the old
        // timer via the job reassignment below, restarting the countdown for the new tap
        // rather than having the old timer race-clear the new reticle out from under it.
        pipeline.onFocusIndicatorChanged = { x, y, indicatorState ->
            _uiState.update { it.copy(focusIndicator = FocusIndicator(x, y, indicatorState)) }
            focusIndicatorHideJob?.cancel()
            if (indicatorState != com.procamera.recorder.camera.FocusController.FocusIndicatorState.Scanning) {
                focusIndicatorHideJob = viewModelScope.launch {
                    delay(1500L)
                    _uiState.update { it.copy(focusIndicator = null) }
                }
            }
        }
    }

    private var focusIndicatorHideJob: Job? = null

    private var meterJob: Job? = null
    private var timerJob: Job? = null
    private var storageJob: Job? = null

    private var lastClippingDetectedMsL = 0L
    private var lastClippingDetectedMsR = 0L
    private val clippingHoldDurationMs = 3_000L
    private var previewSurface: Surface? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Preview lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
    internal fun attachPreviewSurface(surface: Surface) {
        previewSurface = surface
        val wasRecording = _uiState.value.isRecording
        if (!wasRecording) {
            _uiState.update { it.copy(recordingState = RecordingUiState.StartingPreview) }
        }
        viewModelScope.launch {
            val params = _uiState.value.toCameraParams()
            val caps = pipeline.startPreview(surface, params)
            if (wasRecording) {
                // A recording was already in progress — this is the viewfinder returning
                // after [detachPreviewSurface] dropped to an encoder-only session (§4.6
                // screen-off survival). pipeline.startPreview() already folded the surface
                // back into the live session (or logged a failure); recordingState stays
                // Recording either way — only refresh capabilities if they came back. Meter
                // polling is already running (started when this recording began, or earlier
                // still at the original preview) — startMeterPolling() is a no-op here.
                if (caps != null) _uiState.update { it.copy(capabilities = caps, errorMessage = null) }
                return@launch
            }
            if (caps != null) {
                // Meter usable for a sound check before REC — see startMeterPolling's doc.
                startMeterPolling()
                // Enumerate lenses for the lens selector.
                val allLenses = try { capabilityInspector.allRearLenses() } catch (e: Exception) { emptyList() }
                val supportedConfigs = try {
                    capabilityInspector.supportedVideoConfigs(caps.cameraId)
                } catch (e: Exception) {
                    listOf(caps.videoConfig)
                }
                val selectedConfig = supportedConfigs.firstOrNull() ?: caps.videoConfig

                _uiState.update { state ->
                    state.copy(
                        recordingState = RecordingUiState.Previewing,
                        capabilities = caps,
                        availableLenses = allLenses,
                        selectedLensCameraId = caps.cameraId,
                        availableVideoConfigs = supportedConfigs,
                        selectedVideoConfig = selectedConfig,
                        maxZoomRatio = allLenses.firstOrNull { it.cameraId == caps.cameraId }?.maxDigitalZoom ?: 1f,
                        iso = params.iso.coerceIn(caps.isoRange),
                        fps = selectedConfig.frameRate,
                        errorMessage = null,
                    )
                }
                if (!hasRestoredPersistedSettings) {
                    hasRestoredPersistedSettings = true
                    restorePersistedSettings(caps, allLenses, supportedConfigs)
                }
            } else {
                _uiState.update {
                    it.copy(
                        recordingState = RecordingUiState.Idle,
                        errorMessage = "カメラを開けませんでした",
                    )
                }
            }
        }
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
    internal fun detachPreviewSurface() {
        previewSurface = null
        val wasRecording = _uiState.value.isRecording
        viewModelScope.launch {
            // If a recording is in progress, pipeline.detachPreviewSurface() reconfigures
            // the live session to the encoder's InputSurface alone so recording keeps
            // going without a preview consumer (§4.6) — recordingState stays Recording;
            // only the viewfinder is gone until attachPreviewSurface() reattaches it.
            pipeline.detachPreviewSurface()
            if (!wasRecording) {
                stopMeterPolling()
                _uiState.update { it.copy(recordingState = RecordingUiState.Idle) }
            }
        }
    }

    /**
     * Applies [UserPreferencesStore]'s last-saved values on top of the fresh
     * [attachPreviewSurface] state — called exactly once per process (see
     * [hasRestoredPersistedSettings]), right after the first successful preview start.
     * Real-device feedback: re-dialing in lens/zoom/ISO/shutter/WB/EQ on every single
     * launch was the actual friction point.
     *
     * Goes through the existing public setters wherever one exists (reuses their
     * clamping/native-application logic) rather than folding values into a raw
     * `_uiState.update`. The one exception is zoom when the saved lens also needs
     * restoring: [switchLens] resets zoom to 1.0 as part of its own async state update, so
     * a saved zoom must be threaded through its `initialZoomRatio` parameter instead of
     * applied via a separate later [setZoom] call, which would otherwise race it.
     */
    private fun restorePersistedSettings(
        caps: RecordingPipeline.CameraCapabilities,
        allLenses: List<CameraCapabilityInspector.AvailableLens>,
        supportedConfigs: List<CameraCapabilityInspector.VideoConfigCandidate>,
    ) {
        val saved = userPrefs.load()

        saved.iso?.let { setIso(it.coerceIn(caps.isoRange)) }
        saved.exposureTimeNanos?.let {
            setExposureTime(it.coerceIn(caps.exposureTimeRangeNanos.first, caps.exposureTimeRangeNanos.last))
        }
        setWbAuto(saved.wbAuto)
        if (!saved.wbAuto) saved.kelvin?.let(::setKelvin)
        setAfAuto(saved.afAuto)
        setFrameLineAspectRatio(saved.frameLineAspectRatio)
        setInputGainDb(saved.inputGainDb)
        setMonitoringEnabled(saved.monitoringEnabled)
        setStorageLocation(saved.storageLocation)
        saved.segmentDurationMinutes?.let(::setSegmentDuration)
        saved.eqBands.forEachIndexed { i, band ->
            if (band.freqHz != null && band.q != null && band.gainDb != null) {
                setEqBand(i, band.freqHz, band.q, band.gainDb)
            }
        }

        val savedConfig = supportedConfigs.firstOrNull {
            it.width == saved.videoConfigWidth && it.height == saved.videoConfigHeight &&
                it.frameRate == saved.videoConfigFps
        }
        if (savedConfig != null) selectVideoConfig(savedConfig)

        val savedLens = allLenses.firstOrNull { it.cameraId == saved.lensCameraId }
        if (savedLens != null && savedLens.cameraId != caps.cameraId) {
            switchLens(savedLens, initialZoomRatio = saved.zoomRatio ?: 1.0f)
        } else {
            saved.zoomRatio?.let(::setZoom)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lens switching
    // ──────────────────────────────────────────────────────────────────────────

    @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
    /**
     * @param initialZoomRatio Zoom to apply once the switch completes — defaults to 1.0
     * (reset zoom on lens switch, the normal user-initiated case). [restorePersistedSettings]
     * passes the last-used zoom instead, threaded through here rather than applied via a
     * separate post-hoc [setZoom] call, since that would race this method's own async
     * `_uiState.update` (which otherwise unconditionally resets zoomRatio to 1.0).
     */
    fun switchLens(lens: CameraCapabilityInspector.AvailableLens, initialZoomRatio: Float = 1.0f) {
        if (_uiState.value.isRecording) return
        val surface = previewSurface ?: return
        val clampedZoom = initialZoomRatio.coerceIn(1.0f, lens.maxDigitalZoom)

        _uiState.update { it.copy(recordingState = RecordingUiState.StartingPreview) }
        viewModelScope.launch {
            val params = _uiState.value.toCameraParams().copy(zoomRatio = clampedZoom)
            val caps = pipeline.startPreview(surface, params, targetCameraId = lens.cameraId)
            if (caps != null) {
                val supportedConfigs = try {
                    capabilityInspector.supportedVideoConfigs(caps.cameraId)
                } catch (e: Exception) { listOf(caps.videoConfig) }
                val selectedConfig = supportedConfigs.firstOrNull() ?: caps.videoConfig

                _uiState.update { state ->
                    state.copy(
                        recordingState = RecordingUiState.Previewing,
                        capabilities = caps,
                        selectedLensCameraId = lens.cameraId,
                        availableVideoConfigs = supportedConfigs,
                        selectedVideoConfig = selectedConfig,
                        maxZoomRatio = lens.maxDigitalZoom,
                        zoomRatio = clampedZoom,
                        fps = selectedConfig.frameRate,
                        errorMessage = null,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(recordingState = RecordingUiState.Previewing, errorMessage = "レンズ切り替えに失敗しました")
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Video config selection
    // ──────────────────────────────────────────────────────────────────────────

    fun selectVideoConfig(config: CameraCapabilityInspector.VideoConfigCandidate) {
        if (_uiState.value.isRecording) return
        pipeline.selectVideoConfig(config)
        _uiState.update { it.copy(selectedVideoConfig = config, fps = config.frameRate) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Recording lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @androidx.annotation.RequiresPermission(
        allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO],
    )
    fun startRecording() {
        if (_uiState.value.recordingState != RecordingUiState.Previewing) return
        _uiState.update { it.copy(recordingState = RecordingUiState.StartingRecording) }

        // Started here, before the (async) pipeline build, not on Event.Started: a
        // camera|microphone foreground service must be started while the app is still
        // in the foreground (the REC tap itself) — on API 31+, starting one from
        // background can throw ForegroundServiceStartNotAllowedException. On a slow
        // real-device pipeline build, the user could background the app mid-setup before
        // Event.Started ever fires, missing that window. A brief notification flash on
        // immediate failure is an acceptable trade-off (handled in the Failed branch).
        startRecordingService()

        viewModelScope.launch {
            pipeline.startRecording { event ->
                when (event) {
                    is RecordingPipeline.Event.Started -> {
                        _uiState.update {
                            it.copy(
                                recordingState = RecordingUiState.Recording,
                                outputDirectory = event.outputDirectory,
                                recordingElapsedMs = 0L,
                                currentSegment = 0,
                                errorMessage = null,
                            )
                        }
                        _meterState.update { it.copy(xrunCount = 0, ringBufferOverrunCount = 0) }
                        startRecordingJobs()
                    }
                    is RecordingPipeline.Event.Failed -> {
                        _uiState.update {
                            it.copy(recordingState = RecordingUiState.Previewing, errorMessage = "録画エラー: ${event.message}")
                        }
                        stopRecordingJobs()
                        stopRecordingService()
                    }
                    RecordingPipeline.Event.Stopped -> {}
                    is RecordingPipeline.Event.Stats -> {
                        _meterState.update { it.copy(xrunCount = event.xrunCount, ringBufferOverrunCount = event.ringBufferOverrunCount) }
                    }
                }
            }
        }
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
    fun stopRecording() {
        if (_uiState.value.recordingState != RecordingUiState.Recording) return
        _uiState.update { it.copy(recordingState = RecordingUiState.Stopping) }
        viewModelScope.launch {
            stopRecordingJobs()
            pipeline.stopRecording()
            stopRecordingService()
            val surface = previewSurface
            if (surface != null) {
                val caps = pipeline.startPreview(surface, _uiState.value.toCameraParams())
                _uiState.update { state ->
                    state.copy(
                        recordingState = if (caps != null) RecordingUiState.Previewing else RecordingUiState.Idle,
                    )
                }
                _meterState.update {
                    it.copy(
                        peakDbL = -120f, peakDbR = -120f, rmsDbL = -120f, rmsDbR = -120f,
                        isClippingHeldL = false, isClippingHeldR = false,
                    )
                }
            } else {
                _uiState.update { it.copy(recordingState = RecordingUiState.Idle) }
            }
        }
    }

    /**
     * Same start/stop branch as the on-screen REC button's onClick (MainScreen's
     * RecordButtonBar), factored out so a hardware trigger — MainActivity's
     * `dispatchKeyEvent` override for `KEYCODE_CAMERA` (Xperia's dedicated shutter key,
     * per Sony__________.pdf's "カメラキー(シャッターボタン)の割り当て") — doesn't have to
     * duplicate it. Safe to call regardless of current state: [startRecording]/
     * [stopRecording] each no-op unless the recording state actually permits them.
     */
    @Suppress("MissingPermission") // see startRecording()/stopRecording()'s own annotations;
    // this is only reachable once MainScreen (and therefore PermissionGate) is composed.
    fun toggleRecording() {
        if (_uiState.value.isRecording) stopRecording() else startRecording()
    }

    /** Still-photo capture (§Photo mode) — see [RecordingPipeline.capturePhoto]'s doc.
     * PREVIEWING only, not while RECORDING (crashes — see that method's real-device doc);
     * the on-screen button already greys out via [CameraUiState.canCapturePhoto], this is
     * just the same guard's second line of defense for any other caller. */
    @Suppress("MissingPermission") // only reachable once MainScreen (and PermissionGate) is composed
    fun capturePhoto() {
        pipeline.capturePhoto()
    }

    /**
     * Long-press-to-focus on the preview (§AF/MFモード, §4.1) — MainScreen's preview
     * gesture handler calls this on a long-press (not a plain tap, which already toggles
     * the control sidebar — see that call site's doc). [normalizedX]/[normalizedY] must
     * be [0,1] coordinates within the *rendered preview Surface's* own bounds, not the
     * enclosing layout box — see [RecordingPipeline.requestTapToFocus]'s doc.
     */
    fun onPreviewLongPressToFocus(normalizedX: Float, normalizedY: Float) {
        pipeline.requestTapToFocus(normalizedX, normalizedY)
    }

    /**
     * Shutter half-press equivalent (§AF/MFモード) — a touchscreen shutter button has no
     * real two-stage press, so [MainScreen]'s Photo-mode shutter triggers this on
     * `ACTION_DOWN` (before [capturePhoto] fires on release), mirroring a physical
     * camera's half-press-to-focus-then-fully-press-to-shoot gesture. Focuses at frame
     * center — this app has no "last selected AF point" concept outside of an explicit
     * long-press, and center is the same default a fully-auto point-and-shoot camera
     * uses. No-op outside Photo mode (Video mode's shutter is the REC indicator, which
     * doesn't have a press-and-hold gesture to hang this off).
     */
    fun onShutterHalfPress() {
        if (_uiState.value.captureMode != CaptureMode.Photo) return
        pipeline.requestTapToFocus(0.5f, 0.5f)
    }

    /** Switches which action the shutter performs (§CaptureMode's doc). Disallowed
     * mid-recording — MainScreen's mode toggle already greys itself out via
     * [CameraUiState.isRecording], this is the second line of defense so a stray call
     * can't switch a live take out from under the hardware key. */
    fun setCaptureMode(mode: CaptureMode) {
        if (_uiState.value.isRecording) return
        _uiState.update { it.copy(captureMode = mode) }
    }

    /**
     * Single entry point for "the shutter was pressed" — the Xperia hardware camera key
     * (MainActivity.dispatchKeyEvent) and the on-screen shutter control both call this
     * rather than [toggleRecording]/[capturePhoto] directly, so which one actually fires
     * always follows [CameraUiState.captureMode] (§CaptureMode's doc).
     */
    fun onShutterPressed() {
        when (_uiState.value.captureMode) {
            CaptureMode.Photo -> capturePhoto()
            CaptureMode.Video -> toggleRecording()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Camera parameter controls
    // ──────────────────────────────────────────────────────────────────────────

    fun setIso(iso: Int) {
        _uiState.update { it.copy(iso = iso) }
        pipeline.updateCameraParams(_uiState.value.toCameraParams())
    }

    fun setExposureTime(nanos: Long) {
        _uiState.update { it.copy(exposureTimeNanos = nanos, shutterPreset = null) }
        pipeline.updateCameraParams(_uiState.value.toCameraParams())
    }

    fun setShutterPreset(preset: CaptureRangeClamper.ShutterPreset) {
        _uiState.update { it.copy(shutterPreset = preset, exposureTimeNanos = preset.exposureTimeNanos()) }
        pipeline.updateCameraParams(_uiState.value.toCameraParams())
    }

    fun setFocusDistance(diopters: Float) {
        _uiState.update { it.copy(focusDistanceDiopters = diopters, afAuto = false) }
        pipeline.updateCameraParams(_uiState.value.toCameraParams())
    }

    fun setAfAuto(auto: Boolean) {
        val nextFocus = if (!auto && lastAutoFocus != null) lastAutoFocus!! else _uiState.value.focusDistanceDiopters
        _uiState.update { it.copy(afAuto = auto, focusDistanceDiopters = nextFocus) }
        pipeline.updateCameraParams(_uiState.value.toCameraParams())
    }

    fun setWbAuto(auto: Boolean) {
        val nextKelvin = if (!auto && lastAutoKelvin != null) lastAutoKelvin!! else _uiState.value.kelvin
        val nextGains = if (!auto) lastAutoGains else null
        _uiState.update { it.copy(wbAuto = auto, kelvin = nextKelvin, manualWbGains = nextGains) }
        pipeline.updateCameraParams(_uiState.value.toCameraParams())
    }

    fun setKelvin(kelvin: Double) {
        _uiState.update { it.copy(wbAuto = false, kelvin = kelvin, manualWbGains = null) }
        pipeline.updateCameraParams(_uiState.value.toCameraParams())
    }

    fun setZoom(zoomRatio: Float) {
        val clamped = zoomRatio.coerceIn(1f, _uiState.value.maxZoomRatio.coerceAtLeast(1f))
        _uiState.update { it.copy(zoomRatio = clamped) }
        pipeline.updateCameraParams(_uiState.value.toCameraParams())
    }

    fun toggleControls() {
        _uiState.update { it.copy(showControls = !it.showControls) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Audio controls
    // ──────────────────────────────────────────────────────────────────────────

    fun setEqBand(bandIndex: Int, freqHz: Float, q: Float, gainDb: Float) {
        _uiState.update { state ->
            val updated = state.eqBands.toMutableList()
            if (bandIndex in updated.indices) {
                updated[bandIndex] = updated[bandIndex].copy(freqHz = freqHz, q = q, gainDb = gainDb)
            }
            state.copy(eqBands = updated)
        }
        pipeline.setEqBand(bandIndex, freqHz, q, gainDb)
    }

    fun setEqBandGain(bandIndex: Int, gainDb: Float) {
        val band = _uiState.value.eqBands.getOrNull(bandIndex) ?: return
        setEqBand(bandIndex, band.freqHz, band.q, gainDb)
    }

    fun setEqBandFreq(bandIndex: Int, freqHz: Float) {
        val band = _uiState.value.eqBands.getOrNull(bandIndex) ?: return
        setEqBand(bandIndex, freqHz, band.q, band.gainDb)
    }

    fun setEqBandQ(bandIndex: Int, q: Float) {
        val band = _uiState.value.eqBands.getOrNull(bandIndex) ?: return
        setEqBand(bandIndex, band.freqHz, q, band.gainDb)
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        _uiState.update { it.copy(monitoringEnabled = enabled) }
        pipeline.setMonitoringEnabled(enabled)
    }

    fun setInputGainDb(gainDb: Float) {
        _uiState.update { it.copy(inputGainDb = gainDb) }
        pipeline.setInputGainDb(gainDb)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Settings
    // ──────────────────────────────────────────────────────────────────────────

    fun openSettings() = _uiState.update { it.copy(settings = it.settings.copy(showSettingsSheet = true)) }
    fun closeSettings() = _uiState.update { it.copy(settings = it.settings.copy(showSettingsSheet = false)) }

    fun setStorageLocation(location: StorageLocation) {
        if (location is StorageLocation.Custom) {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    location.uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                Log.w(TAG, "takePersistableUriPermission failed", e)
            }
        }
        _uiState.update { it.copy(settings = it.settings.copy(storageLocation = location)) }
        pipeline.setStorageLocation(location)
    }

    fun setSegmentDuration(minutes: Int) {
        _uiState.update { it.copy(settings = it.settings.copy(segmentDurationMinutes = minutes)) }
        pipeline.setSegmentDurationMinutes(minutes)
    }

    /**
     * Preview-only composition guide — no pipeline call, unlike [setStorageLocation]/
     * [setSegmentDuration]: it never touches the encoder's output aspect ratio. See
     * [FrameLineAspectRatio]'s doc for why.
     */
    fun setFrameLineAspectRatio(ratio: FrameLineAspectRatio) {
        _uiState.update { it.copy(settings = it.settings.copy(frameLineAspectRatio = ratio)) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI-only state mutations
    // ──────────────────────────────────────────────────────────────────────────

    fun setActivePanel(panel: ControlPanel) = _uiState.update { it.copy(activePanel = panel) }
    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    // ──────────────────────────────────────────────────────────────────────────
    // Background jobs (active only during recording)
    // ──────────────────────────────────────────────────────────────────────────

    private fun startRecordingJobs() {
        startElapsedTimer()
        startStorageMonitor()
    }

    private fun stopRecordingJobs() {
        timerJob?.cancel(); storageJob?.cancel()
        timerJob = null; storageJob = null
    }

    /**
     * Starts [RecordingService] as a foreground (camera|microphone) service for the
     * duration of the recording — §4.6 screen-off survival. See RecordingService's doc
     * for exactly what this does and does not cover (it does not own the pipeline).
     */
    private fun startRecordingService() {
        val context = getApplication<Application>()
        androidx.core.content.ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, com.procamera.recorder.service.RecordingService::class.java),
        )
    }

    private fun stopRecordingService() {
        val context = getApplication<Application>()
        context.stopService(android.content.Intent(context, com.procamera.recorder.service.RecordingService::class.java))
    }

    /**
     * Pulls per-channel peakDb/rmsDb at ~30fps. Pull (Choreographer-style polling) chosen
     * over JNI push per §4.2's "no JNI calls from audio callback thread" rule. 30fps rather
     * than a tighter 60fps: this loop now runs continuously for as long as preview is up
     * (see the lifecycle note below), not just while recording, so real-device thermal
     * testing showed it's a meaningful *sustained* background contributor to heat/battery
     * (4x JNI calls + a StateFlow update + a Canvas recomposition, every tick, indefinitely)
     * — a VU-style meter reads just as usable at 30fps as 60fps, so halving the rate here is
     * a real reduction in steady-state load for no visible cost, unlike trimming anything on
     * the recording-critical path itself.
     *
     * Tied to *preview* lifecycle (started in [attachPreviewSurface], stopped in
     * [detachPreviewSurface]), not recording — so the meter is usable for a sound check
     * before REC, matching [RecordingPipeline.ensureAudioEngineStarted]'s doc for why the
     * native audio engine itself now also starts at preview time. No-op if already running
     * (idempotent, mirroring the pipeline side) since [attachPreviewSurface] can re-enter
     * this while a recording is already in progress (viewfinder reattaching).
     */
    private fun startMeterPolling() {
        if (meterJob != null) return
        meterJob = viewModelScope.launch {
            while (isActive) {
                delay(33L)
                val peakL = pipeline.nativeEngine.peakDb(CHANNEL_LEFT)
                val peakR = pipeline.nativeEngine.peakDb(CHANNEL_RIGHT)
                val rmsL = pipeline.nativeEngine.rmsDb(CHANNEL_LEFT)
                val rmsR = pipeline.nativeEngine.rmsDb(CHANNEL_RIGHT)
                val nowMs = System.currentTimeMillis()
                if (peakL > CLIPPING_THRESHOLD_DB) lastClippingDetectedMsL = nowMs
                if (peakR > CLIPPING_THRESHOLD_DB) lastClippingDetectedMsR = nowMs
                val clippingHeldL = (nowMs - lastClippingDetectedMsL) < clippingHoldDurationMs
                val clippingHeldR = (nowMs - lastClippingDetectedMsR) < clippingHoldDurationMs
                _meterState.update {
                    it.copy(
                        peakDbL = peakL, peakDbR = peakR, rmsDbL = rmsL, rmsDbR = rmsR,
                        isClippingHeldL = clippingHeldL, isClippingHeldR = clippingHeldR,
                    )
                }
            }
        }
    }

    private fun stopMeterPolling() {
        meterJob?.cancel()
        meterJob = null
        lastClippingDetectedMsL = 0L
        lastClippingDetectedMsR = 0L
        _meterState.update {
            it.copy(
                peakDbL = -120f, peakDbR = -120f, rmsDbL = -120f, rmsDbR = -120f,
                isClippingHeldL = false, isClippingHeldR = false,
            )
        }
    }

    private fun startElapsedTimer() {
        val startMs = System.currentTimeMillis()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(500L)
                _uiState.update { it.copy(recordingElapsedMs = System.currentTimeMillis() - startMs) }
            }
        }
    }

    private fun startStorageMonitor() {
        storageJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val stat = StatFs(Environment.getExternalStorageDirectory().path)
                    val freeBytes = stat.availableBytes
                    val bitrateBytes =
                        ((_uiState.value.selectedVideoConfig?.bitrate ?: VIDEO_BITRATE_FALLBACK_BPS) +
                            AUDIO_BITRATE_BPS) / 8L
                    val remainingSeconds = if (bitrateBytes > 0) freeBytes / bitrateBytes else Long.MAX_VALUE
                    _uiState.update { it.copy(storageRemainingSeconds = remainingSeconds) }
                } catch (e: Exception) {
                    Log.w(TAG, "Storage check failed", e)
                }
                delay(10_000L)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        thermalMonitor.stop()
        stopRecordingJobs()
        meterJob?.cancel(); meterJob = null
        pipeline.stopAll()
        // Best-effort hygiene: if the ViewModel is torn down while the foreground service
        // is up (this is itself the process-death/task-swipe scenario RecordingService's
        // doc says is NOT covered — the pipeline is dying here too), at least don't leave
        // a stuck notification/wake lock behind if the process happens to survive this.
        stopRecordingService()
        val app = getApplication<Application>() as? com.procamera.recorder.ProCameraApplication
        if (app?.activeRecordingPipeline === pipeline) app.activeRecordingPipeline = null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun CameraUiState.toCameraParams() = CameraParams(
        iso = this.iso,
        exposureTimeNanos = this.exposureTimeNanos,
        focusDistanceDiopters = this.focusDistanceDiopters,
        kelvin = this.kelvin,
        manualWbGains = this.manualWbGains,
        wbAuto = this.wbAuto,
        afAuto = this.afAuto,
        fps = this.selectedVideoConfig?.frameRate ?: this.fps,
        zoomRatio = this.zoomRatio,
    )

    /**
     * The subset of [CameraUiState] that [UserPreferencesStore] actually persists, used as
     * an equality-comparable key for the auto-save debounce (see [init]'s doc for why the
     * raw state flow can't be debounced directly — the audio meter fields update ~30x/sec
     * for as long as preview is up and would otherwise mean the debounce window never
     * closes). Deliberately excludes everything telemetry/derived: peakDb/rmsDb, thermal
     * status, elapsed time, xrun/overrun counts, capabilities, availableLenses, etc.
     */
    private data class PersistSnapshot(
        val lensCameraId: String?,
        val iso: Int,
        val exposureTimeNanos: Long,
        val zoomRatio: Float,
        val kelvin: Double,
        val wbAuto: Boolean,
        val afAuto: Boolean,
        val frameLineAspectRatio: FrameLineAspectRatio,
        val inputGainDb: Float,
        val monitoringEnabled: Boolean,
        val storageLocation: StorageLocation,
        val segmentDurationMinutes: Int,
        val videoConfig: CameraCapabilityInspector.VideoConfigCandidate?,
        val eqBands: List<EqBandState>,
    ) {
        constructor(state: CameraUiState) : this(
            lensCameraId = state.selectedLensCameraId,
            iso = state.iso,
            exposureTimeNanos = state.exposureTimeNanos,
            zoomRatio = state.zoomRatio,
            kelvin = state.kelvin,
            wbAuto = state.wbAuto,
            afAuto = state.afAuto,
            frameLineAspectRatio = state.settings.frameLineAspectRatio,
            inputGainDb = state.inputGainDb,
            monitoringEnabled = state.monitoringEnabled,
            storageLocation = state.settings.storageLocation,
            segmentDurationMinutes = state.settings.segmentDurationMinutes,
            videoConfig = state.selectedVideoConfig,
            eqBands = state.eqBands,
        )
    }

    private companion object {
        const val TAG = "CameraControlViewModel"
        const val CLIPPING_THRESHOLD_DB = -0.1f
        const val CHANNEL_LEFT = 0
        const val CHANNEL_RIGHT = 1
        const val AUDIO_BITRATE_BPS = 256_000
        const val VIDEO_BITRATE_FALLBACK_BPS = 20_000_000
    }
}
