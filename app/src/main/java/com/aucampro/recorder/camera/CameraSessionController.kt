package com.aucampro.recorder.camera

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
import android.util.Log
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

    private companion object {
        const val TAG = "CameraSessionController"
    }

    /** Per-frame result listener for FocusController integration. */
    fun interface CaptureResultListener {
        fun onCaptureResult(result: TotalCaptureResult)
    }

    private val callbackThread = HandlerThread("CameraCallback").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val callbackExecutor = Executor { command -> callbackHandler.post(command) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var openCameraId: String? = null

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
        openCameraId = cameraId

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
     * Rebuilds the `CameraCaptureSession`'s output surface set **without** closing and
     * reopening the underlying `CameraDevice` when [cameraId] matches the device this
     * controller already has open — falls back to the full [stop]+[startRepeating] path
     * otherwise (no device open yet, or a different `cameraId` — e.g. a lens switch on a
     * multi-camera device, which has no in-place equivalent).
     *
     * **実機計測 (2026-07-18)**: at the recording-start call site, the previous
     * `stop()`+`startRepeating()` pair cost ~1000ms end-to-end (breakdown:
     * `abortCaptures`+`session.close`+`device.close` ~220ms, `openCamera` ~130ms,
     * `createCaptureSession` ~430-450ms, `setRepeatingRequest` ~200ms). Device-reuse only
     * removes the `device.close`+`openCamera` portion (~250-320ms) — `createCaptureSession`
     * dominates and costs essentially the same regardless of whether the device was freshly
     * reopened, since it's the HAL's own session-configuration handshake, not a
     * device-open cost. This is still worth doing for two reasons: (1) it's a real,
     * measured ~30% latency cut with no correctness downside — Camera2 documents that
     * `createCaptureSession` on an already-open device implicitly supersedes/aborts the
     * previous session, so an explicit `session.close()` beforehand is unnecessary (and
     * would only add back a synchronous state-transition wait this method is specifically
     * avoiding); (2) keeping the `CameraDevice` itself open means its 3A (AF/AE/AWB)
     * convergence state is never torn down and rebuilt from scratch, which is the
     * suspected mechanism behind a separately-reported "AF hunts again at recording
     * start" symptom — unconfirmed without dedicated real-device AF-state testing, but
     * plausible and costs nothing to get for free alongside the latency win.
     *
     * The remaining ~650ms (mostly `createCaptureSession`) is NOT eliminated by this
     * method — see [com.aucampro.recorder.muxer.PtsClockDomain.isStarted]'s doc for the
     * fix that makes the *recorded file* correct regardless of how long this call takes:
     * anchoring the A/V epoch to the first real video frame rather than to when this
     * method was invoked.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun reconfigureSession(
        cameraId: String,
        outputSurfaces: List<Surface>,
        requestFactory: ManualCaptureRequestFactory,
        params: CameraParams,
        repeatingTargets: List<Surface> = outputSurfaces,
    ) {
        require(outputSurfaces.isNotEmpty()) { "At least one output surface required" }

        val existingDevice = device
        if (existingDevice == null || openCameraId != cameraId) {
            stop()
            startRepeating(cameraId, outputSurfaces, requestFactory, params, repeatingTargets)
            return
        }

        try {
            session?.abortCaptures()
        } catch (e: Exception) {
            Log.w(TAG, "reconfigureSession: abortCaptures failed, proceeding anyway", e)
        }

        // Deliberately no session?.close() here — see this method's doc for why creating
        // the new session directly on the still-open device is both faster and documented
        // as safe (the new session supersedes the old one).
        val captureSession = createSession(existingDevice, outputSurfaces)
        session = captureSession

        activeSurfaces = repeatingTargets
        activeRequestFactory = requestFactory
        activeParams = params

        captureSession.setRepeatingRequest(
            buildRequest(existingDevice, requestFactory, params),
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
     *
     * **実機で発見**: [device]/[session] being non-null here is *not* a guarantee the
     * underlying `CameraDevice` is still usable — Camera2 can close/invalidate it
     * out-of-band (another app taking the camera, the OS reclaiming it, or this class's
     * own [stop] running on another thread between this method's null-check and its
     * actual use) independently of this class clearing its own [device]/[session] fields,
     * which only happens synchronously inside [stop] itself. Confirmed on real hardware
     * via [submitSingleRequest] (identical hazard, same fields): a long-press-to-focus
     * gesture landing right as the session was torn down crashed the whole app with
     * `IllegalStateException: CameraDevice was already closed` from inside
     * `createCaptureRequest`. Swallowing it here is correct, not just convenient — the
     * caller (a UI gesture or a slider drag) has no useful recovery action for "the
     * session died out from under this specific request," and the *next* successful
     * [startRepeating] already re-establishes a working session regardless.
     */
    fun updateCaptureParams(params: CameraParams) {
        val currentSession = session ?: return
        val currentDevice = device ?: return
        val factory = activeRequestFactory ?: return

        activeParams = params
        try {
            currentSession.setRepeatingRequest(
                buildRequest(currentDevice, factory, params),
                makeCaptureCallback(),
                callbackHandler,
            )
        } catch (e: IllegalStateException) {
            Log.w(TAG, "updateCaptureParams: session/device closed mid-call, ignoring", e)
        }
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
     *
     * **実機で発見**: same hazard as [updateCaptureParams]'s doc — [device] being non-null
     * here does not guarantee it is still open. Confirmed on real hardware: a plain photo
     * capture tap crashed the whole app with `IllegalStateException: CameraDevice was
     * already closed` from inside `createCaptureRequest` when the session had been torn
     * down (e.g. screen lock) between this method's null-check and its use.
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
        try {
            val builder = currentDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(jpegSurface)
            // **実機で発見(2026-07-20)**: `TEMPLATE_STILL_CAPTURE`に`CONTROL_AE_MODE_ON`を
            // 設定すると、`CONTROL_AE_TARGET_FPS_RANGE`を付けても付けなくても、このSony HALで
            // カメラクライアントが停止する(`Camera3-Device: reconfigureCamera: Can't idle
            // device in 5.000000 seconds!` → クライアント強制切断 → プレビュー復帰不能)。
            // よってExposureMode.AUTO中の静止画撮影はViewModel層(canCapturePhoto/
            // capturePhoto()の実効ガード)で完全に止めており、ここに到達するのは常にMANUALの
            // 時のみ——このメソッド自体はExposureModeを分岐しない。docs/
            // VIDEO_FPS_STUTTER_INVESTIGATION_2026-07-20.md §4.3参照。
            requestFactory.applyManualExposure(builder, params.iso, params.exposureTimeNanos, params.fps)
            requestFactory.applyFocus(builder, params.focusDistanceDiopters, params.afAuto)
            requestFactory.applyWhiteBalance(builder, params)
            requestFactory.applyZoom(builder, params.zoomRatio)
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            currentSession.capture(builder.build(), null, callbackHandler)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "capturePhoto: session/device closed mid-call, ignoring", e)
        }
    }

    /** Closes the session and device; does not stop the callback thread — see [release]. */
    fun stop() {
        // abortCaptures() before close(): some OEM HALs throw CameraAccessException
        // (CAMERA_ERROR "cancelRequest") out of close() if a repeating request is still
        // actively streaming when it's called — abort discards in-flight requests first,
        // which close() alone is not documented to guarantee. Session may already be
        // mid-teardown (e.g. a device disconnect racing this call), so this is
        // best-effort, same as the other CameraAccessException/IllegalStateException
        // guards in this file.
        try {
            session?.abortCaptures()
        } catch (e: Exception) {
            Log.w(TAG, "stop: abortCaptures failed, proceeding to close anyway", e)
        }
        session?.close()
        device?.close()
        session = null
        device = null
        openCameraId = null
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
    ): CaptureRequest = buildRequestBuilder(device, factory, params).build()

    private fun buildRequestBuilder(
        device: CameraDevice,
        factory: ManualCaptureRequestFactory,
        params: CameraParams,
    ): CaptureRequest.Builder {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        activeSurfaces.forEach { builder.addTarget(it) }
        // [ExposureMode]分岐(動画/プレビューのリピートリクエスト専用)。[capturePhoto]は
        // ExposureModeを分岐せず常にapplyManualExposureを使う——AUTOモード中は
        // ViewModel層(CameraUiState.canCapturePhoto)で静止画撮影自体を止めているため、
        // capturePhotoへ到達するのは常にMANUALの時のみ。実機でTEMPLATE_STILL_CAPTUREに
        // AE_MODE_ONを使うとこのHALが不安定になることが分かったため、静止画専用の
        // Auto適用関数は存在しない(ManualCaptureRequestFactoryのapplyAutoExposureForVideo
        // のdoc参照)。
        when (params.exposureMode) {
            ExposureMode.AUTO -> factory.applyAutoExposureForVideo(builder, params.fps)
            ExposureMode.MANUAL -> factory.applyManualExposure(builder, params.iso, params.exposureTimeNanos, params.fps)
        }
        factory.applyFocus(builder, params.focusDistanceDiopters, params.afAuto)
        factory.applyWhiteBalance(builder, params)
        factory.applyZoom(builder, params.zoomRatio)
        return builder
    }

    /**
     * Rebuilds the repeating request from the currently active params/factory (same base as
     * [updateCaptureParams]), then lets [configure] override specific keys on top — the seam
     * [FocusController] uses (§4.1 tap-to-focus) to inject `CONTROL_AF_MODE`/
     * `CONTROL_AF_REGIONS`/`CONTROL_AF_TRIGGER` for a scan, and later to lock focus at the
     * converged distance.
     *
     * **Why this updates the *repeating* request rather than firing one isolated
     * `capture()`**: `AF_TRIGGER_START` is a self-resetting flag — the HAL treats it as
     * "start a scan" for one frame even when the request object carrying it is the one
     * `setRepeatingRequest` keeps reusing for every subsequent frame (this is standard
     * Camera2 behavior, not specific to this app) — but `CONTROL_AF_MODE` is *not*
     * self-resetting: if only a single one-shot `capture()` carried `AF_MODE_AUTO` while
     * the repeating request kept flowing with the previous `AF_MODE_OFF`, the very next
     * repeating-request frame would immediately cancel the scan the trigger just started.
     * Folding the trigger into the repeating request itself avoids that race entirely.
     */
    fun submitSingleRequest(configure: (CaptureRequest.Builder) -> Unit) {
        val currentDevice = device ?: return
        val currentSession = session ?: return
        val factory = activeRequestFactory ?: return
        val params = activeParams ?: return
        // 実機で発見: [device] passing the null-check above does not guarantee it is still
        // open — see [updateCaptureParams]'s doc for the full explanation (this method is
        // where the crash was actually reproduced: a long-press-to-focus gesture landing
        // right as the session was being torn down hit `IllegalStateException:
        // CameraDevice was already closed` inside `createCaptureRequest`, below).
        try {
            val builder = buildRequestBuilder(currentDevice, factory, params)
            configure(builder)
            currentSession.setRepeatingRequest(builder.build(), makeCaptureCallback(), callbackHandler)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "submitSingleRequest: session/device closed mid-call, ignoring", e)
        }
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
                        if (cont.isActive) {
                            cont.resume(camera)
                        } else {
                            // The coroutine was cancelled while the camera was opening
                            // (e.g. the caller backgrounded mid-open) — resume() on an
                            // already-cancelled continuation silently discards `camera`
                            // without closing it, leaking the exclusive camera hardware
                            // lock. That can block every future openCamera() call, by
                            // this app or another, until the OS eventually reclaims it.
                            camera.close()
                        }
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
                    if (cont.isActive) {
                        cont.resume(session)
                    } else {
                        session.close()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
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
