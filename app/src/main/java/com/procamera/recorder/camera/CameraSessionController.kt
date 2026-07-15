package com.procamera.recorder.camera

import android.Manifest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.annotation.RequiresPermission
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns the real `CameraDevice`/`CameraCaptureSession` lifecycle (§4.1).
 *
 * **Phase 4 additions** (UI wiring):
 * - [startRepeating] now accepts `List<Surface>` so a preview surface can be included
 *   alongside the encoder's InputSurface. Session is created with all provided surfaces in
 *   one call; Camera2 requires a new session when the surface set changes, so switching
 *   from preview-only → preview+encoder closes and recreates the session.
 * - [updateCaptureParams] modifies the repeating request in-place (no session restart
 *   needed — `setRepeatingRequest` replaces the previous one atomically at the HAL level).
 *   This is how ISO/shutter/WB/focus changes become live-visible in the viewfinder.
 * - [captureResultListener] seam lets [FocusController] observe AF state transitions
 *   from each CaptureResult without coupling it to this class directly.
 */
class CameraSessionController(private val cameraManager: CameraManager) {

    /** Per-frame result listener for FocusController integration. */
    fun interface CaptureResultListener {
        fun onCaptureResult(result: TotalCaptureResult)
    }

    private val callbackThread = HandlerThread("CameraCallback").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val callbackExecutor = Executor { command -> callbackHandler.post(command) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    // Preserved so updateCaptureParams() can rebuild the request without caller re-supplying them.
    private var activeSurfaces: List<Surface> = emptyList()
    private var activeRequestFactory: ManualCaptureRequestFactory? = null
    private var activeParams: CameraParams? = null

    /** Optional: set before [startRepeating] to receive per-frame CaptureResult callbacks. */
    var captureResultListener: CaptureResultListener? = null

    /**
     * Opens [cameraId] and configures a session with [outputSurfaces] (e.g. just a preview
     * surface, or preview + encoder InputSurface). Starts a repeating request immediately,
     * targeting [repeatingTargets] (defaults to all of [outputSurfaces]).
     *
     * Replaces the previous single-surface API. Passing the encoder's InputSurface and/or
     * a preview Surface is the caller's responsibility:
     * - Preview-only: `listOf(previewSurface)`
     * - Recording: `listOf(previewSurface, encoderInputSurface)` or just `listOf(encoderInputSurface)`
     *   when no preview is available (smoke-test mode).
     *
     * **[repeatingTargets] vs [outputSurfaces]**: every surface in [outputSurfaces] is
     * configured into the `CameraCaptureSession` (so it CAN be targeted by some capture
     * request later), but only [repeatingTargets] is actually targeted by the *continuous*
     * repeating request `buildRequest()` builds — a surface configured but excluded from
     * that list sits idle until something else (e.g. [capturePhoto]'s one-shot
     * `TEMPLATE_STILL_CAPTURE`) explicitly targets it. This distinction matters for a
     * still-photo `ImageReader`: **実機で発見・修正** — with no [repeatingTargets] override,
     * such a reader defaulted to also being targeted by the repeating *preview* request,
     * meaning it silently full-res-JPEG-encoded every single preview frame (confirmed via
     * `capturePhoto()` producing two saved files from one tap — the reader's queue already
     * had a stale frame from the last "repeating" delivery sitting in it before the actual
     * one-shot capture's image even arrived) — a large, pointless continuous encode cost
     * `LuminanceHistogramReader` deliberately *does* want (it needs every preview frame,
     * just downsampled small — see its own doc), but a photo reader should not.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun startRepeating(
        cameraId: String,
        outputSurfaces: List<Surface>,
        requestFactory: ManualCaptureRequestFactory,
        params: CameraParams,
        repeatingTargets: List<Surface> = outputSurfaces,
    ) {
        require(outputSurfaces.isNotEmpty()) { "At least one output surface required" }

        val cameraDevice = openCamera(cameraId)
        device = cameraDevice

        val captureSession = createSession(cameraDevice, outputSurfaces)
        session = captureSession

        activeSurfaces = repeatingTargets
        activeRequestFactory = requestFactory
        activeParams = params

        captureSession.setRepeatingRequest(
            buildRequest(cameraDevice, requestFactory, params),
            makeCaptureCallback(),
            callbackHandler,
        )
    }

    /**
     * Replaces the current repeating request with updated [params]. Call this whenever the
     * user moves a slider (ISO, shutter, focus, WB). Safe to call on any thread; the actual
     * `setRepeatingRequest` is posted to [callbackHandler] implicitly via the Camera2 API.
     *
     * No-op if no session is active (e.g. called before [startRepeating] or after [stop]).
     */
    fun updateCaptureParams(params: CameraParams) {
        val currentSession = session ?: return
        val currentDevice = device ?: return
        val factory = activeRequestFactory ?: return

        activeParams = params
        currentSession.setRepeatingRequest(
            buildRequest(currentDevice, factory, params),
            makeCaptureCallback(),
            callbackHandler,
        )
    }

    /**
     * Fires a single `TEMPLATE_STILL_CAPTURE` request targeting [jpegSurface] — which must
     * already be part of the currently active session's surface set (added at
     * [startRepeating] time; Camera2 requires every capture target to have been included
     * when the session was configured, it can't be added ad hoc) — alongside the existing
     * repeating preview/record request, which continues completely unaffected (this is a
     * one-shot [CameraCaptureSession.capture], not a replacement of the repeating request).
     * [jpegOrientation] sets `CaptureRequest.JPEG_ORIENTATION` so the saved file is tagged
     * for correct display — same rotation math as the video path's
     * `MediaMuxer.setOrientationHint` (see `DeviceOrientationTracker`'s doc), just via the
     * stills-specific capture-request key instead of a container-level hint.
     *
     * No-op if no session is active.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun capturePhoto(
        jpegSurface: Surface,
        requestFactory: ManualCaptureRequestFactory,
        params: CameraParams,
        jpegOrientation: Int,
    ) {
        val currentDevice = device ?: return
        val currentSession = session ?: return
        val builder = currentDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(jpegSurface)
        requestFactory.applyManualExposure(builder, params.iso, params.exposureTimeNanos, params.fps)
        requestFactory.applyFocus(builder, params.focusDistanceDiopters, params.afAuto)
        requestFactory.applyWhiteBalance(builder, params)
        requestFactory.applyZoom(builder, params.zoomRatio)
        builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
        currentSession.capture(builder.build(), null, callbackHandler)
    }

    /** Closes the session and device; does not stop the callback thread — see [release]. */
    fun stop() {
        session?.close()
        device?.close()
        session = null
        device = null
        activeSurfaces = emptyList()
        activeRequestFactory = null
        activeParams = null
    }

    /** Call once, after [stop], when this controller will never be reused. */
    fun release() {
        stop()
        callbackThread.quitSafely()
    }

    /** Exposed for callers that need characteristics before/without opening the device. */
    fun characteristicsFor(cameraId: String): CameraCharacteristics =
        cameraManager.getCameraCharacteristics(cameraId)

    // ──────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────────

    private fun buildRequest(
        device: CameraDevice,
        factory: ManualCaptureRequestFactory,
        params: CameraParams,
    ): CaptureRequest {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        activeSurfaces.forEach { builder.addTarget(it) }
        factory.applyManualExposure(builder, params.iso, params.exposureTimeNanos, params.fps)
        factory.applyFocus(builder, params.focusDistanceDiopters, params.afAuto)
        factory.applyWhiteBalance(builder, params)
        factory.applyZoom(builder, params.zoomRatio)
        return builder.build()
    }

    private fun makeCaptureCallback(): CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                captureResultListener?.onCaptureResult(result)
            }
        }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun openCamera(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cont.resume(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("Camera $cameraId disconnected"),
                            )
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("Camera $cameraId error: $error"),
                            )
                        }
                    }
                },
                callbackHandler,
            )
        }

    private suspend fun createSession(
        cameraDevice: CameraDevice,
        surfaces: List<Surface>,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val outputConfigs = surfaces.map { OutputConfiguration(it) }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            callbackExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cont.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("Camera session configuration failed"),
                        )
                    }
                }
            },
        )
        cameraDevice.createCaptureSession(config)
    }
}
