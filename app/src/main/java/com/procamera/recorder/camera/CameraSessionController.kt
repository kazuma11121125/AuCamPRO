package com.procamera.recorder.camera

import android.Manifest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
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
 * Owns the real `CameraDevice`/`CameraCaptureSession` lifecycle (§4.1) — the piece the
 * rest of Phase 3's Camera2 glue ([ManualCaptureRequestFactory], [CaptureRangeClamper])
 * has been waiting to be driven by. Untested framework glue (a real `CameraManager` is
 * required); this is exactly the code path the record-pipeline smoke test milestone
 * (docs/ARCHITECTURE.md's Phase 4 note) exists to exercise on a physical device before
 * UI/Service work is layered on top.
 *
 * A dedicated [HandlerThread] runs every Camera2 callback (device state, session state)
 * off the caller's thread, matching the thread model in docs/ARCHITECTURE.md. No
 * per-frame `CaptureCallback` is installed: video PTS calibration against the camera's
 * clock domain was removed after a real-device finding (see [com.procamera.recorder.muxer
 * .PtsClockDomain]'s class doc) — `VideoEncoder`'s `presentationTimeUs` needs no
 * calibration against `CaptureResult.SENSOR_TIMESTAMP`.
 */
class CameraSessionController(private val cameraManager: CameraManager) {

    private val callbackThread = HandlerThread("CameraCallback").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val callbackExecutor = Executor { command -> callbackHandler.post(command) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    /**
     * Opens [cameraId], configures a session against [outputSurface] (the video encoder's
     * InputSurface — §4.1's single-surface capture target for the smoke-test milestone;
     * a preview surface is added once UI work lands), and starts a repeating manual
     * capture request built from [requestFactory]'s exposure/focus/WB settings.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun startRepeating(
        cameraId: String,
        outputSurface: Surface,
        requestFactory: ManualCaptureRequestFactory,
        iso: Int,
        exposureTimeNanos: Long,
        fps: Int,
        focusDistanceDiopters: Float,
        kelvin: Double,
    ) {
        val cameraDevice = openCamera(cameraId)
        device = cameraDevice

        val captureSession = createSession(cameraDevice, listOf(outputSurface))
        session = captureSession

        val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(outputSurface)
        requestFactory.applyManualExposure(builder, iso, exposureTimeNanos, fps)
        requestFactory.applyManualFocusLocked(builder, focusDistanceDiopters)
        requestFactory.applyManualWhiteBalance(builder, kelvin)

        captureSession.setRepeatingRequest(builder.build(), null, callbackHandler)
    }

    /** Closes the session and device (does not stop the callback thread — see [release]). */
    fun stop() {
        session?.close()
        device?.close()
        session = null
        device = null
    }

    /** Call once, after [stop], when this controller will never be reused. */
    fun release() {
        stop()
        callbackThread.quitSafely()
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun openCamera(cameraId: String): CameraDevice = suspendCancellableCoroutine { cont ->
        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cont.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera $cameraId disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera $cameraId error: $error"))
                }
            },
            callbackHandler,
        )
    }

    private suspend fun createSession(cameraDevice: CameraDevice, surfaces: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
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
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera session configuration failed"))
                    }
                },
            )
            cameraDevice.createCaptureSession(config)
        }

    /** Exposed for callers that need to read characteristics before/without opening the device. */
    fun characteristicsFor(cameraId: String): CameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
}
