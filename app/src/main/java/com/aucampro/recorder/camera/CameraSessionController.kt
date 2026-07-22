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
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresPermission
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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

        // 2026-07-23 real-device finding (SO-51C): during Phase 2 feasibility testing, a
        // burst of rapid Activity relaunches (config-change storm, e.g. from an unstable
        // wireless-charging placement) mid-recording triggered repeated
        // reconfigureRecordingSurfacesLocked() calls; one of the resulting
        // createCaptureSession attempts appears to have never called back
        // (onConfigured/onConfigureFailed) at all. Since [openCamera]/[createSession] used
        // a bare suspendCancellableCoroutine with no timeout, that left the coroutine
        // parked forever — and since both run inside RecordingPipeline's `sessionMutex
        // .withLock`, the mutex itself stayed locked forever too, wedging every subsequent
        // camera operation (including the REC-stop path) — an ANR, not a crash, since the
        // main thread's Looper itself kept running fine; only camera-mutex-guarded work
        // was stuck. This timeout bounds that wait so a HAL that never calls back produces
        // a normal (catchable) failure instead of a permanent app-wide wedge. 5s is
        // generously above every measured createCaptureSession cost (Phase 1: 205-483ms)
        // while still being well inside what a user would tolerate/notice as "frozen."
        const val CAMERA_OPERATION_TIMEOUT_MS = 5_000L
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

    // docs/PERSISTENT_ENCODER_SURFACE_DESIGN_2026-07-21.md Phase 2 — the callback object
    // passed to the MOST RECENT successful setRepeatingRequest call, whichever method made
    // it (startRepeating/reconfigureSession/updateCaptureParams/updateRepeatingTargets/
    // submitSingleRequest — all go through [issueRepeatingRequest] now). Camera2 invokes
    // onCaptureSequenceCompleted/onCaptureSequenceAborted on *that specific callback
    // object* once its sequence is superseded — tracking "whichever one is current"
    // (rather than only the one from a specific method) is what lets
    // [updateRepeatingTargetsAwaitingDrain]/[stopRepeatingAwaitingDrain] correctly await
    // the *actually currently active* sequence's drain, even if e.g. a slider drag
    // (updateCaptureParams) reissued the repeating request one or more times after the
    // encoder was added — awaiting a stale reference would await an already-completed
    // event that will never fire again, timing out every time.
    private var activeRepeatingCallback: RepeatingCallback? = null

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

        val request = buildRequest(cameraDevice, requestFactory, params)
        issueRepeatingRequest(captureSession, request, CameraSessionMetrics.RepeatingRequestReason.START_REPEATING)
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

        CameraSessionMetrics.traceSync("AuCam:abortCaptures") {
            try {
                session?.abortCaptures()
            } catch (e: Exception) {
                Log.w(TAG, "reconfigureSession: abortCaptures failed, proceeding anyway", e)
            }
        }
        // docs/CAMERA_SESSION_LATENCY_2026-07-21.md Phase 1 — reconfigureSession() is only
        // ever called for a recording-related surface-set change (record-start, or a
        // detach/reattach mid-recording), so CameraSessionMetrics.activeRecordingAttemptId()
        // always refers to the attempt this call belongs to, unlike VideoEncoder/
        // MuxerController's async callbacks (see their own docs for why those needed a
        // constructor-captured id instead — this call is synchronous within the same
        // startRecording() invocation that set the id, so no next attempt can race it here).
        val metricsAttemptId = CameraSessionMetrics.activeRecordingAttemptId()
        CameraSessionMetrics.logStage(metricsAttemptId, "T3_abortCapturesComplete")

        // Deliberately no session?.close() here — see this method's doc for why creating
        // the new session directly on the still-open device is both faster and documented
        // as safe (the new session supersedes the old one).
        CameraSessionMetrics.logStage(metricsAttemptId, "T4_createSessionBegin")
        val captureSession = createSession(existingDevice, outputSurfaces)
        session = captureSession
        CameraSessionMetrics.logStage(metricsAttemptId, "T5_createSessionConfigured")

        activeSurfaces = repeatingTargets
        activeRequestFactory = requestFactory
        activeParams = params

        val request = buildRequest(existingDevice, requestFactory, params)
        issueRepeatingRequest(captureSession, request, CameraSessionMetrics.RepeatingRequestReason.SESSION_RECONFIGURE)
        CameraSessionMetrics.logStage(metricsAttemptId, "T6_setRepeatingRequestComplete")
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
            val request = buildRequest(currentDevice, factory, params)
            issueRepeatingRequest(currentSession, request, CameraSessionMetrics.RepeatingRequestReason.PARAM_UPDATE)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "updateCaptureParams: session/device closed mid-call, ignoring", e)
        }
    }

    /**
     * Reissues the repeating request against [newTargets] **without** touching the
     * `CameraCaptureSession`'s configured output surface set — no `abortCaptures()`, no
     * `createCaptureSession()`. Every surface in [newTargets] must already be part of the
     * surface set the session was last configured with (via [startRepeating] or
     * [reconfigureSession]'s `outputSurfaces`); Camera2 throws `IllegalArgumentException`
     * from `createCaptureRequest`/`setRepeatingRequest` otherwise — callers are responsible
     * for keeping that invariant (see docs/PERSISTENT_ENCODER_SURFACE_DESIGN_2026-07-21.md).
     * That invariant violation is a caller bug, not a runtime condition to swallow, so
     * (unlike the `IllegalStateException` guard below) it is deliberately left to propagate.
     *
     * This is the fast path that design doc's Phase 2 adds: adding/removing the
     * (session-resident but idle-until-targeted) persistent encoder Surface from the live
     * repeating request on record start/stop, instead of paying [reconfigureSession]'s
     * ~200-480ms `createCaptureSession` cost every cycle.
     *
     * **Transactional** (2026-07-23 review finding): [activeSurfaces] is only mutated once
     * `setRepeatingRequest` has actually been accepted — a mid-call
     * `IllegalStateException` (session/device closed) rolls it back to its previous value
     * rather than leaving this class's bookkeeping claiming a surface set that was never
     * actually applied at the HAL level, which would have silently corrupted every
     * subsequent [updateCaptureParams]/[submitSingleRequest] request built from it.
     *
     * Returns `true` iff the repeating request was actually reissued — `false` for "no
     * session active" (a real no-op) as well as a caught `IllegalStateException` (a failed
     * attempt) — callers must not treat either as a silent success; see
     * [updateRepeatingTargetsAwaitingDrain]'s doc for why this specifically matters at
     * record-stop time.
     */
    fun updateRepeatingTargets(newTargets: List<Surface>): Boolean {
        val currentSession = session ?: return false
        val currentDevice = device ?: return false
        val factory = activeRequestFactory ?: return false
        val params = activeParams ?: return false

        val previousSurfaces = activeSurfaces
        activeSurfaces = newTargets
        return try {
            val request = buildRequest(currentDevice, factory, params)
            issueRepeatingRequest(currentSession, request, CameraSessionMetrics.RepeatingRequestReason.REPEATING_TARGETS_UPDATE)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "updateRepeatingTargets: session/device closed mid-call, rolling back", e)
            activeSurfaces = previousSurfaces
            false
        }
    }

    /**
     * [updateRepeatingTargets] plus a drain guarantee: suspends until the repeating
     * sequence this call *supersedes* (i.e. whatever [activeRepeatingCallback] was before
     * this call) has been confirmed by the framework as either fully completed
     * (`onCaptureSequenceCompleted`) or aborted (`onCaptureSequenceAborted`) — meaning no
     * further frames from that PREVIOUS sequence can still be in flight toward any Surface
     * it was targeting.
     *
     * **Why this exists** (2026-07-23 review finding, docs/
     * PERSISTENT_ENCODER_SURFACE_DESIGN_2026-07-21.md Phase 2): plain
     * [updateRepeatingTargets] only guarantees the *new* repeating request has been
     * accepted — Camera2 does not guarantee the old one's already-in-flight captures (sensor
     * exposure already started, HAL already processing) stop delivering to their surfaces
     * the instant a replacement is set; a handful can still land afterward. At record-stop,
     * the surface being dropped from repeating is the encoder's — if
     * `VideoEncoder.signalEndOfStream()` races one of those trailing frames, the encoder's
     * internal buffer source (which is explicitly designed to tolerate an asynchronous
     * external producer like Camera2, but only up to the declared end of input) can receive
     * a frame after being told there won't be one, which is undefined/unsafe. The
     * pre-Phase-2 stop sequence avoided this for free because `stop()`'s `abortCaptures()`
     * *does* guarantee immediate discard of all in-flight captures — this method restores
     * an equivalent guarantee for the fast (no session teardown) path by waiting for the
     * framework's own completion signal instead.
     *
     * Returns `false` (without rolling back [activeSurfaces] — the new repeating request
     * *did* take effect, only the drain confirmation didn't arrive in time) if the previous
     * sequence doesn't drain within [timeoutMs]. **Callers must treat `false` as "cannot
     * guarantee it's safe to signal end-of-stream yet"** and fall back to a harder stop
     * (e.g. this class's [stop], whose `abortCaptures()` gives the same guarantee
     * unconditionally) rather than proceeding — see this method's own reasoning above.
     * Returns `true` immediately (no wait) if there was no previous sequence to drain (e.g.
     * this is the very first repeating request on a fresh session).
     */
    suspend fun updateRepeatingTargetsAwaitingDrain(newTargets: List<Surface>, timeoutMs: Long = 500L): Boolean {
        val previousCallback = activeRepeatingCallback
        val drainDeferred = previousCallback?.let { cb ->
            CompletableDeferred<Unit>().also { deferred -> cb.onSequenceFinished = { deferred.complete(Unit) } }
        }
        val issued = updateRepeatingTargets(newTargets)
        if (!issued) {
            previousCallback?.onSequenceFinished = null
            return false
        }
        if (drainDeferred == null) return true
        val drained = withTimeoutOrNull(timeoutMs) { drainDeferred.await() } != null
        previousCallback.onSequenceFinished = null
        if (!drained) {
            Log.w(TAG, "updateRepeatingTargetsAwaitingDrain: previous sequence did not drain within ${timeoutMs}ms")
        }
        return drained
    }

    /**
     * Stops the repeating request outright (`CameraCaptureSession.stopRepeating()`) rather
     * than replacing it — for the case [updateRepeatingTargetsAwaitingDrain]'s caller has no
     * valid non-empty target list to replace it with (docs/
     * PERSISTENT_ENCODER_SURFACE_DESIGN_2026-07-21.md Phase 2, 2026-07-23 review finding:
     * e.g. record-stop while backgrounded with no preview Surface *and* no histogram reader
     * — `setRepeatingRequest` with zero targets throws `IllegalArgumentException`
     * immediately, so this must be the branch taken instead, not a fallback attempted after
     * that throw). `stopRepeating()` triggers `onCaptureSequenceAborted` on the previously
     * active sequence, which this method awaits the same way
     * [updateRepeatingTargetsAwaitingDrain] does, for the same reason (safe to
     * `signalEndOfStream()` afterward). Leaves [activeSurfaces] empty and the session
     * itself still open — a later [updateRepeatingTargets]/[reconfigureSession] resumes
     * repeating normally.
     */
    suspend fun stopRepeatingAwaitingDrain(timeoutMs: Long = 500L): Boolean {
        val currentSession = session ?: return true
        val previousCallback = activeRepeatingCallback
        val drainDeferred = previousCallback?.let { cb ->
            CompletableDeferred<Unit>().also { deferred -> cb.onSequenceFinished = { deferred.complete(Unit) } }
        }
        try {
            currentSession.stopRepeating()
        } catch (e: Exception) {
            Log.w(TAG, "stopRepeatingAwaitingDrain: stopRepeating failed", e)
            previousCallback?.onSequenceFinished = null
            return false
        }
        activeSurfaces = emptyList()
        if (drainDeferred == null) return true
        val drained = withTimeoutOrNull(timeoutMs) { drainDeferred.await() } != null
        previousCallback.onSequenceFinished = null
        if (!drained) {
            Log.w(TAG, "stopRepeatingAwaitingDrain: previous sequence did not drain within ${timeoutMs}ms")
        }
        return drained
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
        CameraSessionMetrics.traceSync("AuCam:abortCaptures") {
            try {
                session?.abortCaptures()
            } catch (e: Exception) {
                Log.w(TAG, "stop: abortCaptures failed, proceeding to close anyway", e)
            }
        }
        session?.close()
        device?.close()
        session = null
        device = null
        openCameraId = null
        activeSurfaces = emptyList()
        activeRequestFactory = null
        activeParams = null
        activeRepeatingCallback = null
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
    ): CaptureRequest.Builder = CameraSessionMetrics.traceSync("AuCam:buildCaptureRequest") {
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
        CameraSessionMetrics.onCaptureRequestBuilderCreated()
        builder
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
            val request = builder.build()
            issueRepeatingRequest(currentSession, request, CameraSessionMetrics.RepeatingRequestReason.FOCUS_REQUEST)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "submitSingleRequest: session/device closed mid-call, ignoring", e)
        }
    }

    /**
     * `CaptureCallback` used for every repeating request this class issues. Beyond the
     * existing per-frame [captureResultListener] plumbing, it exposes [onSequenceFinished]
     * — invoked from `onCaptureSequenceCompleted`/`onCaptureSequenceAborted`, i.e. once
     * *this specific instance's* repeating sequence has been fully superseded/drained —
     * for docs/PERSISTENT_ENCODER_SURFACE_DESIGN_2026-07-21.md Phase 2's
     * [updateRepeatingTargetsAwaitingDrain]/[stopRepeatingAwaitingDrain] to await.
     */
    private inner class RepeatingCallback : CameraCaptureSession.CaptureCallback() {
        var onSequenceFinished: (() -> Unit)? = null

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            CameraSessionMetrics.onCaptureCallbackInvoked()
            val startNanos = SystemClock.elapsedRealtimeNanos()
            val sampled = CameraSessionMetrics.shouldSampleThisFrame()
            if (sampled) Trace.beginSection("AuCam:captureCallbackSample")
            val listener = captureResultListener
            if (listener != null) {
                CameraSessionMetrics.onCaptureResultListenerInvoked()
                listener.onCaptureResult(result)
            }
            if (sampled) Trace.endSection()
            CameraSessionMetrics.recordCaptureCallbackDurationNanos(
                SystemClock.elapsedRealtimeNanos() - startNanos,
            )
        }

        override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            onSequenceFinished?.invoke()
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            onSequenceFinished?.invoke()
        }
    }

    /**
     * Every `setRepeatingRequest` call in this class goes through here so
     * [activeRepeatingCallback] always reflects whichever callback is *actually* live right
     * now (see that field's doc) — required for [updateRepeatingTargetsAwaitingDrain] to
     * await the right sequence regardless of which method last touched the repeating
     * request.
     */
    private fun issueRepeatingRequest(
        captureSession: CameraCaptureSession,
        request: CaptureRequest,
        reason: CameraSessionMetrics.RepeatingRequestReason,
    ) {
        val callback = RepeatingCallback()
        CameraSessionMetrics.traceSync("AuCam:setRepeatingRequest") {
            captureSession.setRepeatingRequest(request, callback, callbackHandler)
        }
        activeRepeatingCallback = callback
        CameraSessionMetrics.onSetRepeatingRequest(reason)
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun openCamera(cameraId: String): CameraDevice =
        CameraSessionMetrics.traceAsync("AuCam:openCamera") {
            CameraSessionMetrics.onOpenCamera()
            // 2026-07-23 real-device finding — see CAMERA_OPERATION_TIMEOUT_MS's doc: bounds
            // the wait so a HAL that never calls back can't wedge sessionMutex forever.
            // withTimeout cancels the coroutine below on expiry; the existing
            // `cont.isActive` checks in each callback already handle a late callback
            // arriving after that (discarding/closing the resource instead of resuming an
            // already-cancelled continuation) — that path already existed for the
            // caller-backgrounded-mid-open case, so no further changes needed here.
            withTimeout(CAMERA_OPERATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    cameraManager.openCamera(
                        cameraId,
                        object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) {
                                if (cont.isActive) {
                                    cont.resume(camera)
                                } else {
                                    // The coroutine was cancelled while the camera was opening
                                    // (e.g. the caller backgrounded mid-open, or this call
                                    // timed out) — resume() on an already-cancelled
                                    // continuation silently discards `camera` without closing
                                    // it, leaking the exclusive camera hardware lock. That can
                                    // block every future openCamera() call, by this app or
                                    // another, until the OS eventually reclaims it.
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
            }
        }

    private suspend fun createSession(
        cameraDevice: CameraDevice,
        surfaces: List<Surface>,
    ): CameraCaptureSession = CameraSessionMetrics.traceAsync("AuCam:createSession") {
        CameraSessionMetrics.onCreateCaptureSession()
        // 2026-07-23 real-device finding — see CAMERA_OPERATION_TIMEOUT_MS's doc. This is
        // the specific call implicated in the real ANR this timeout was added for: a rapid
        // Activity-relaunch storm mid-recording drove repeated createCaptureSession calls,
        // and one of them appears to have never invoked onConfigured/onConfigureFailed at
        // all, permanently parking this coroutine — and, since this runs inside
        // RecordingPipeline's `sessionMutex.withLock`, permanently locking that mutex too,
        // wedging every subsequent camera operation app-wide (an ANR, since the main
        // thread's own Looper was otherwise fine).
        withTimeout(CAMERA_OPERATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
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
    }
}
