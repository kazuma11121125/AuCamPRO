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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
    
    private var lastAutoKelvin: Double? = null
    private var lastAutoGains: android.hardware.camera2.params.RggbChannelVector? = null
    private var lastAutoFocus: Float? = null

    init {
        // Registers this ViewModel's single RecordingPipeline instance with the
        // Application-level crash handler so an uncaught exception can attempt a
        // best-effort finalize of whatever segment is currently open (§4.6) — see
        // ProCameraApplication and RecordingPipeline.emergencyFinalizeCurrentSegment's docs.
        (app as? com.procamera.recorder.ProCameraApplication)?.activeRecordingPipeline = pipeline

        pipeline.onAutoWbGainsMeasured = { gains, kelvin ->
            lastAutoKelvin = kelvin
            lastAutoGains = gains
            if (_uiState.value.wbAuto) {
                if (kotlin.math.abs(_uiState.value.kelvin - kelvin) > 50.0) {
                    _uiState.update { it.copy(kelvin = kelvin) }
                }
            }
        }
        pipeline.onAutoFocusMeasured = { focus ->
            lastAutoFocus = focus
            if (_uiState.value.afAuto) {
                if (kotlin.math.abs(_uiState.value.focusDistanceDiopters - focus) > 0.1f) {
                    _uiState.update { it.copy(focusDistanceDiopters = focus) }
                }
            }
        }
    }

    private var meterJob: Job? = null
    private var timerJob: Job? = null
    private var storageJob: Job? = null

    private var lastClippingDetectedMs = 0L
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
                // Recording either way — only refresh capabilities if they came back.
                if (caps != null) _uiState.update { it.copy(capabilities = caps, errorMessage = null) }
                return@launch
            }
            if (caps != null) {
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
                _uiState.update { it.copy(recordingState = RecordingUiState.Idle) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lens switching
    // ──────────────────────────────────────────────────────────────────────────

    @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
    fun switchLens(lens: CameraCapabilityInspector.AvailableLens) {
        if (_uiState.value.isRecording) return
        val surface = previewSurface ?: return

        _uiState.update { it.copy(recordingState = RecordingUiState.StartingPreview) }
        viewModelScope.launch {
            val params = _uiState.value.toCameraParams().copy(zoomRatio = 1.0f) // reset zoom on lens switch
            val caps = pipeline.startPreview(surface, params)
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
                        zoomRatio = 1.0f,
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
                                xrunCount = 0,
                                ringBufferOverrunCount = 0,
                                errorMessage = null,
                            )
                        }
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
                        _uiState.update { it.copy(xrunCount = event.xrunCount, ringBufferOverrunCount = event.ringBufferOverrunCount) }
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
                        peakDb = -120f, rmsDb = -120f, isClippingHeld = false,
                    )
                }
            } else {
                _uiState.update { it.copy(recordingState = RecordingUiState.Idle) }
            }
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

    // ──────────────────────────────────────────────────────────────────────────
    // UI-only state mutations
    // ──────────────────────────────────────────────────────────────────────────

    fun setActivePanel(panel: ControlPanel) = _uiState.update { it.copy(activePanel = panel) }
    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    // ──────────────────────────────────────────────────────────────────────────
    // Background jobs (active only during recording)
    // ──────────────────────────────────────────────────────────────────────────

    private fun startRecordingJobs() {
        startMeterPolling()
        startElapsedTimer()
        startStorageMonitor()
    }

    private fun stopRecordingJobs() {
        meterJob?.cancel(); timerJob?.cancel(); storageJob?.cancel()
        meterJob = null; timerJob = null; storageJob = null
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
     * Pulls peakDb/rmsDb at ~60fps. Pull (Choreographer-style polling) chosen over
     * JNI push per §4.2's "no JNI calls from audio callback thread" rule.
     */
    private fun startMeterPolling() {
        meterJob = viewModelScope.launch {
            while (isActive) {
                delay(16L)
                val peak = pipeline.nativeEngine.peakDb()
                val rms = pipeline.nativeEngine.rmsDb()
                val nowMs = System.currentTimeMillis()
                if (peak > CLIPPING_THRESHOLD_DB) lastClippingDetectedMs = nowMs
                val clippingHeld = (nowMs - lastClippingDetectedMs) < clippingHoldDurationMs
                _uiState.update { it.copy(peakDb = peak, rmsDb = rms, isClippingHeld = clippingHeld) }
            }
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
        stopRecordingJobs()
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

    private companion object {
        const val TAG = "CameraControlViewModel"
        const val CLIPPING_THRESHOLD_DB = -0.1f
        const val AUDIO_BITRATE_BPS = 256_000
        const val VIDEO_BITRATE_FALLBACK_BPS = 20_000_000
    }
}
