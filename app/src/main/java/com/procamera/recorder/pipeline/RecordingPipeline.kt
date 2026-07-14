package com.procamera.recorder.pipeline

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.procamera.recorder.audio.NativeEngineBridge
import com.procamera.recorder.camera.CameraCapabilityInspector
import com.procamera.recorder.camera.CameraParams
import com.procamera.recorder.camera.CameraSessionController
import com.procamera.recorder.camera.CaptureRangeClamper
import com.procamera.recorder.camera.ColorTemperatureConverter
import com.procamera.recorder.camera.ManualCaptureRequestFactory
import com.procamera.recorder.encoder.AudioEncoder
import com.procamera.recorder.encoder.VideoEncoder
import com.procamera.recorder.muxer.PtsClockDomain
import com.procamera.recorder.muxer.SegmentedMuxerController
import com.procamera.recorder.ui.viewmodel.StorageLocation
import java.io.File
import java.nio.ByteBuffer

/**
 * Unified camera/audio/encode/mux pipeline for Phase 4's full UI build.
 *
 * **State machine**: IDLE → PREVIEWING ↔ RECORDING → IDLE
 *
 * - [startPreview]: Opens the camera, streams to a [Surface] (viewfinder). No encoders run.
 * - [startRecording]: Reconfigures the session to include the encoder's InputSurface alongside
 *   the preview, then starts the full encode/mux pipeline. Must be in PREVIEWING state.
 * - [stopRecording]: Drains and finalises all encoders/muxers, then restarts preview automatically.
 * - [stopAll]: Tears everything down unconditionally (call from ViewModel.onCleared).
 *
 * **Parameter updates**: [updateCameraParams] applies changes (ISO, shutter, focus, WB) to the
 * live repeating request without reopening the session.
 *
 * **Metric access**: [nativeEngine] is exposed as a `val` so the ViewModel can poll
 * [NativeEngineBridge.peakDb] / [NativeEngineBridge.rmsDb] / [NativeEngineBridge.hardwareXRunCount]
 * at 60 fps without any extra plumbing through this class.
 *
 * スモークテストマイルストーン（docs/ARCHITECTURE.md §Phase4）で動作確認済みのパイプラインを
 * ベースにし、UI / Foreground Serviceが扱える本格的な状態機械に拡張した。
 */
class RecordingPipeline(private val context: Context) {

    // ──────────────────────────────────────────────────────────────────────────────
    // Public API types
    // ──────────────────────────────────────────────────────────────────────────────

    sealed interface Event {
        /** Emitted once the muxer is open and frames are being written. */
        data class Started(val outputDirectory: File) : Event
        /** Emitted when a fatal error prevents recording from starting or continuing. */
        data class Failed(val message: String) : Event
        /** Emitted after [stopRecording] successfully drains all encoders. */
        data object Stopped : Event
        /**
         * Emitted periodically during recording with the latest stats. The ViewModel
         * collects these to update its StateFlow without polling.
         */
        data class Stats(
            val dropFrameCount: Int,
            val xrunCount: Int,
            val ringBufferOverrunCount: Int,
        ) : Event
    }

    /**
     * Camera capability snapshot filled once [startPreview] succeeds. Propagated to
     * the ViewModel so the UI can set slider ranges.
     */
    data class CameraCapabilities(
        val isoRange: IntRange,
        val exposureTimeRangeNanos: LongRange,
        val minFocusDistanceDiopters: Float,
        val supportsManualWb: Boolean,
        val hardwareLevel: Int,
        val videoConfig: CameraCapabilityInspector.VideoConfigCandidate,
        val cameraId: String,
    )

    // ──────────────────────────────────────────────────────────────────────────────
    // Infrastructure (long-lived across preview/recording cycles)
    // ──────────────────────────────────────────────────────────────────────────────

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val capabilityInspector = CameraCapabilityInspector(cameraManager)
    private val sessionController = CameraSessionController(cameraManager)

    /**
     * Exposed so the ViewModel can poll [NativeEngineBridge.peakDb]/[NativeEngineBridge.rmsDb]
     * and update the audio meter StateFlow at ~60 fps. The engine is started in
     * [startRecording] and stopped in [stopRecordingInternal].
     */
    val nativeEngine = NativeEngineBridge()

    // ──────────────────────────────────────────────────────────────────────────────
    // State preserved across recording cycles
    // ──────────────────────────────────────────────────────────────────────────────

    private var selectedLens: CameraCapabilityInspector.LensInfo? = null
    private var capabilities: CameraCapabilities? = null
    private var requestFactory: ManualCaptureRequestFactory? = null
    private var currentParams = CameraParams()
    private var previewSurface: Surface? = null

    // User-configurable settings
    private var nextVideoConfig: CameraCapabilityInspector.VideoConfigCandidate? = null
    private var storageLocation: StorageLocation = StorageLocation.AppPrivate
    private var segmentDurationMinutes: Int = 5

    private enum class PipelineState { IDLE, PREVIEWING, RECORDING }
    private var pipelineState = PipelineState.IDLE

    var onAutoWbGainsMeasured: ((android.hardware.camera2.params.RggbChannelVector, Double) -> Unit)? = null
    var onAutoFocusMeasured: ((Float) -> Unit)? = null

    // ──────────────────────────────────────────────────────────────────────────────
    // Encoder / muxer (only alive during RECORDING)
    // ──────────────────────────────────────────────────────────────────────────────

    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var muxerController: SegmentedMuxerController? = null
    private var ptsClockDomain: PtsClockDomain? = null
    private var currentOutputDir: File? = null

    // ──────────────────────────────────────────────────────────────────────────────
    // Public methods
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the camera and streams to [surface]. If [surface] is null the camera is opened
     * only for capability inspection (used during the first-run permission flow before the
     * SurfaceView is ready).
     *
     * Returns the [CameraCapabilities] snapshot so the ViewModel can populate slider ranges.
     * On failure returns null and logs the error; the caller should handle gracefully.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun startPreview(
        surface: Surface?,
        params: CameraParams = currentParams,
    ): CameraCapabilities? {
        if (pipelineState == PipelineState.RECORDING) {
            Log.w(TAG, "startPreview called during RECORDING — ignored")
            return capabilities
        }
        // Stop any existing preview session before re-opening (surface may have changed).
        if (pipelineState == PipelineState.PREVIEWING) stopPreviewSession()

        return try {
            val lens = capabilityInspector.findStandardRearLens()
                ?: error("No standard rear lens found on this device")

            // Default candidate for the CameraCapabilities snapshot (used to populate slider
            // ranges etc.). The user's actual Settings selection (selectVideoConfig()) is
            // consulted later, at encoder-creation time in startRecording() — preview doesn't
            // care about the eventual encoder's resolution, so there is no need to restart
            // the preview session just because the user picked a different video config.
            // §1.2's primary/fallback pair (4K HEVC / 1080p60 H.264) covers the capable real
            // devices this app targets. If neither is actually encodable (e.g. a software
            // encoder capped below 60fps, as on the emulator's swiftshader codec), fall back
            // to the broader candidate list built for the Settings resolution picker rather
            // than failing outright — better to record at a lower spec than not at all.
            val videoConfig = capabilityInspector.videoConfigCandidates().firstOrNull {
                capabilityInspector.isVideoConfigSupported(
                    it.mimeType, it.width, it.height, it.frameRate, it.bitrate,
                )
            } ?: capabilityInspector.supportedVideoConfigs(lens.cameraId).firstOrNull()
                ?: error("No supported video config (checked HEVC 4K30, H.264 1080p60, and the broader fallback list)")

            val characteristics = sessionController.characteristicsFor(lens.cameraId)
            val clamper = CaptureRangeClamper.fromCharacteristics(characteristics)

            val caps = CameraCapabilities(
                isoRange = clamper.sensitivityRange,
                exposureTimeRangeNanos = clamper.exposureTimeRangeNanos,
                minFocusDistanceDiopters = clamper.minFocusDistanceDiopters,
                supportsManualWb = capabilityInspector.supportsManualWhiteBalance(lens.cameraId),
                hardwareLevel = capabilityInspector.hardwareLevel(lens.cameraId),
                videoConfig = videoConfig,
                cameraId = lens.cameraId,
            )

            selectedLens = lens
            capabilities = caps
            requestFactory = ManualCaptureRequestFactory(characteristics)
            currentParams = params
            previewSurface = surface

            var frameCount = 0
            sessionController.captureResultListener = CameraSessionController.CaptureResultListener { result ->
                frameCount++
                if (frameCount % 10 == 0) {
                    if (currentParams?.wbAuto == true) {
                        val gains = result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)
                        if (gains != null) {
                            val k = ColorTemperatureConverter.rggbGainsToKelvin(gains)
                            Log.d("RecordingPipeline", "Auto WB Measured: kelvin=$k, gains=[R=${gains.red}, G_even=${gains.greenEven}, G_odd=${gains.greenOdd}, B=${gains.blue}]")
                            Handler(Looper.getMainLooper()).post {
                                onAutoWbGainsMeasured?.invoke(gains, k)
                            }
                        } else {
                            Log.w("RecordingPipeline", "WB is AUTO but COLOR_CORRECTION_GAINS is null")
                        }
                    }
                    if (currentParams?.afAuto == true) {
                        val focus = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
                        if (focus != null) {
                            Log.d("RecordingPipeline", "Auto AF Measured: focus=$focus")
                            Handler(Looper.getMainLooper()).post {
                                onAutoFocusMeasured?.invoke(focus)
                            }
                        } else {
                            Log.w("RecordingPipeline", "AF is AUTO but LENS_FOCUS_DISTANCE is null")
                        }
                    }
                }
            }

            // If no surface is available yet, defer actually opening the session.
            if (surface != null) {
                sessionController.startRepeating(
                    cameraId = lens.cameraId,
                    outputSurfaces = listOf(surface),
                    requestFactory = requireNotNull(requestFactory),
                    params = params,
                )
                pipelineState = PipelineState.PREVIEWING
            }

            Log.i(TAG, "Preview started: cameraId=${lens.cameraId} videoConfig=$videoConfig")
            caps
        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed", e)
            null
        }
    }

    /**
     * Starts the full encode/mux pipeline. Must be in PREVIEWING state.
     *
     * Session is reconfigured to add the encoder's InputSurface alongside [previewSurface],
     * introducing a brief (~100-200ms) camera freeze during session recreation — acceptable
     * for a recording-start event that the user has explicitly triggered.
     *
     * On success emits [Event.Started]. On any failure emits [Event.Failed] and cleans up.
     */
    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    suspend fun startRecording(onEvent: (Event) -> Unit) {
        if (pipelineState != PipelineState.PREVIEWING) {
            onEvent(Event.Failed("Pipeline is not in PREVIEWING state (current: $pipelineState)"))
            return
        }

        val lens = selectedLens ?: run {
            onEvent(Event.Failed("No lens selected — call startPreview() first"))
            return
        }
        val factory = requestFactory ?: run {
            onEvent(Event.Failed("No request factory — call startPreview() first"))
            return
        }
        val caps = capabilities ?: run {
            onEvent(Event.Failed("Capabilities not yet resolved"))
            return
        }

        try {
            val pts = PtsClockDomain()
            ptsClockDomain = pts
            pts.start()

            // Prefer the user's Settings selection (selectVideoConfig()) when one is set and
            // still valid for this device; otherwise fall back to the default picked at
            // startPreview() time. Re-validated here (not just trusted from the UI's cached
            // selection) since the lens may have changed since the user picked it.
            val recordingVideoConfig = nextVideoConfig?.takeIf {
                capabilityInspector.isVideoConfigSupported(it.mimeType, it.width, it.height, it.frameRate, it.bitrate)
            } ?: caps.videoConfig

            val outputDir = File(
                context.getExternalFilesDir(null),
                "recordings/${System.currentTimeMillis()}",
            )
            outputDir.mkdirs()
            currentOutputDir = outputDir

            val muxer = SegmentedMuxerController(
                outputPathForSegment = { index -> File(outputDir, "segment_$index.mp4").absolutePath },
                segmentDurationUs = segmentDurationMinutes * 60 * 1_000_000L,
            )
            muxerController = muxer

            val video = VideoEncoder(
                mimeType = recordingVideoConfig.mimeType,
                width = recordingVideoConfig.width,
                height = recordingVideoConfig.height,
                frameRate = recordingVideoConfig.frameRate,
                bitrate = recordingVideoConfig.bitrate,
                ptsClockDomain = pts,
                callback = object : VideoEncoder.Callback {
                    override fun onOutputFormatChanged(format: MediaFormat) =
                        muxer.onVideoFormatChanged(format)

                    override fun onEncodedFrame(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) =
                        muxer.onVideoSample(buffer, bufferInfo)

                    override fun onError(exception: Exception) {
                        Log.e(TAG, "VideoEncoder error", exception)
                        onEvent(Event.Failed("VideoEncoder: ${exception.message}"))
                    }
                },
            )
            videoEncoder = video

            val engineError = nativeEngine.start()
            if (engineError != null) error("Audio engine failed to start: $engineError")

            val audio = AudioEncoder(
                sampleRateHz = AUDIO_SAMPLE_RATE_HZ,
                channelCount = AUDIO_CHANNEL_COUNT,
                bitrate = AUDIO_BITRATE_BPS,
                nativeEngine = nativeEngine,
                ptsClockDomain = pts,
                callback = object : AudioEncoder.Callback {
                    override fun onOutputFormatChanged(format: MediaFormat) =
                        muxer.onAudioFormatChanged(format)

                    override fun onEncodedFrame(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) =
                        muxer.onAudioSample(buffer, bufferInfo)

                    override fun onError(exception: Exception) {
                        Log.e(TAG, "AudioEncoder error", exception)
                        onEvent(Event.Failed("AudioEncoder: ${exception.message}"))
                    }
                },
            )
            audioEncoder = audio

            video.start()
            // AudioEncoder.start() seeds PtsClockDomain's audio anchor itself (retrying
            // NativeEngineBridge.getInputTimestamp() for frame-correlation accuracy — see
            // its doc and docs/ARCHITECTURE.md §Phase3). nativeEngine.start() must complete
            // before this call.
            audio.start()

            // Reconfigure the session to include both the preview and the encoder's InputSurface.
            // Camera2 requires closing the current session and opening a new one when the
            // output surface set changes — this causes a brief preview freeze (~100-200ms)
            // at recording-start time, which is an acceptable UX trade-off for v1.
            sessionController.stop()
            val surfaces = listOfNotNull(previewSurface, video.inputSurface)
            sessionController.startRepeating(
                cameraId = lens.cameraId,
                outputSurfaces = surfaces,
                requestFactory = factory,
                params = currentParams,
            )

            pipelineState = PipelineState.RECORDING
            Log.i(TAG, "Recording started → $outputDir")
            onEvent(Event.Started(outputDir))
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            onEvent(Event.Failed(e.message ?: e.toString()))
            stopRecordingInternal(restartPreview = false)
        }
    }

    /**
     * Stops recording: drains all encoders, finalises muxers, then automatically restarts
     * the preview-only session so the viewfinder stays live. Must be called from a
     * coroutine; `awaitEndOfStream()` blocks until the VideoEncoder is fully drained.
     *
     * The stop order follows docs/ARCHITECTURE.md §Phase4's stop-sequence rationale:
     * both capture sources (camera + microphone) are stopped back-to-back, THEN encoders
     * are drained — keeping the Audio tail ≤ 0.75s per the real-device measurement.
     */
    suspend fun stopRecording() {
        if (pipelineState != PipelineState.RECORDING) return
        stopRecordingInternal(restartPreview = true)
    }

    /**
     * Stops everything (preview + recording if active). Call from `ViewModel.onCleared()`.
     */
    fun stopAll() {
        if (pipelineState == PipelineState.RECORDING) {
            stopRecordingInternal(restartPreview = false)
        }
        stopPreviewSession()
        sessionController.release()
        nativeEngine.close()
        pipelineState = PipelineState.IDLE
    }

    /**
     * Sets the video config to use for the next call to [startRecording].
     * No-op while recording.
     */
    fun selectVideoConfig(config: CameraCapabilityInspector.VideoConfigCandidate) {
        if (pipelineState == PipelineState.RECORDING) return
        nextVideoConfig = config
    }

    /** Sets where recordings are saved. Takes effect on the next [startRecording]. */
    fun setStorageLocation(location: StorageLocation) {
        storageLocation = location
    }

    /** Sets how many minutes each segment file should be before a new file is opened. */
    fun setSegmentDurationMinutes(minutes: Int) {
        segmentDurationMinutes = minutes
    }

    /**
     * Applies [params] to the live repeating request without restarting the session.
     * The Camera2 HAL replaces the previous request at the next frame boundary, so
     * viewfinder changes are effectively instant (one frame latency).
     */
    fun updateCameraParams(params: CameraParams) {
        currentParams = params
        sessionController.updateCaptureParams(params)
    }

    fun setEqBand(band: Int, freqHz: Float, q: Float, gainDb: Float) {
        nativeEngine.setEqBandParams(band, freqHz, q, gainDb)
    }

    fun setMonitoringEnabled(enabled: Boolean, outputDeviceId: Int = 0) {
        val error = nativeEngine.setMonitoringEnabled(enabled, outputDeviceId)
        if (error != null) Log.w(TAG, "setMonitoringEnabled($enabled) returned error: $error")
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────────

    private fun stopRecordingInternal(restartPreview: Boolean) {
        // Stop both capture sources back-to-back (see stop-sequence doc above).
        sessionController.stop()
        nativeEngine.stop()

        videoEncoder?.let { encoder ->
            encoder.signalEndOfStream()
            encoder.awaitEndOfStream()
            encoder.stop()
        }
        audioEncoder?.stop()
        muxerController?.stop()

        ptsClockDomain = null
        videoEncoder = null
        audioEncoder = null
        muxerController = null
        pipelineState = PipelineState.IDLE

        // Preview restart itself is *not* done here despite the restartPreview parameter:
        // this is a plain (non-suspend) function, and re-opening the camera session is a
        // suspend call. restartPreview only distinguishes the two callers' intent —
        // stopRecording() (true) relies on the ViewModel calling startPreview() again after
        // it returns; stopAll() (false) tears the pipeline down instead. See stopRecording's
        // doc.
        currentOutputDir?.let { exportToPublicMoviesIfRequested(it) }
        currentOutputDir = null
    }

    /**
     * If [storageLocation] is [StorageLocation.PublicMovies], copies each finalised segment
     * `.mp4` out of the app-private [outputDir] into the shared `Movies/ProCamera` collection
     * via MediaStore (so it shows up in the gallery), then deletes the app-private copy.
     * No-op for [StorageLocation.AppPrivate]. Runs synchronously — safe here since this is
     * always called after encoders/muxer are already fully stopped and drained, off the
     * main thread (`stopRecording()` is suspend and called from a background coroutine).
     */
    private fun exportToPublicMoviesIfRequested(outputDir: File) {
        if (storageLocation != StorageLocation.PublicMovies) return
        val segmentFiles = outputDir.listFiles { f -> f.extension == "mp4" }?.sortedBy { it.name }
        if (segmentFiles.isNullOrEmpty()) return

        val resolver = context.contentResolver
        for (file in segmentFiles) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCamera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Log.e(TAG, "MediaStore insert failed for ${file.name}")
                    continue
                }
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                file.delete()
                Log.i(TAG, "Exported ${file.name} to Movies/ProCamera")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export ${file.name} to MediaStore Movies", e)
            }
        }
    }

    private fun stopPreviewSession() {
        if (pipelineState == PipelineState.PREVIEWING) {
            sessionController.stop()
            pipelineState = PipelineState.IDLE
        }
    }

    private companion object {
        const val TAG = "RecordingPipeline"

        // Must match app/src/main/cpp/engine/OboeFullDuplexEngine.h's kSampleRate/kChannelCount.
        const val AUDIO_SAMPLE_RATE_HZ = 48_000
        const val AUDIO_CHANNEL_COUNT = 2
        const val AUDIO_BITRATE_BPS = 256_000 // §4: "AAC-LC 48kHz Stereo 256kbps"
    }
}
