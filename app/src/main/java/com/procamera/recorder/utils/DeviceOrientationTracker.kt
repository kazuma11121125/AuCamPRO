package com.procamera.recorder.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.view.OrientationEventListener

/**
 * Tracks the phone's *physical* rotation away from its natural (portrait) orientation,
 * continuously, via [OrientationEventListener] — independent of the app window's own
 * orientation, which is locked to `sensorLandscape` (real-device feedback: the UI itself
 * should stay landscape-only) and therefore can't answer "which way is the phone actually
 * being held right now" the way `Display.rotation` normally would.
 *
 * This exists specifically to support recording while physically holding the phone in
 * portrait: [RecordingPipeline] samples [lastKnownDegrees] once at the start of each
 * recording and turns it into an MP4 rotation-matrix hint (see
 * [orientationHintDegreesFor]) so players display the clip upright, without this app ever
 * needing to re-configure the camera session's actual buffer orientation/aspect for
 * different physical holds — see `PreviewSurfaceView`'s doc for why that buffer is
 * deliberately fixed instead.
 */
class DeviceOrientationTracker(context: Context) {
    private var lastKnownDegrees = 0

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            // Quantize to the nearest quadrant — matches the official Android
            // getJpegOrientation() sample's rounding (see orientationHintDegreesFor's doc)
            // and avoids the hint jittering between e.g. 88/89/90 as the phone is held.
            lastKnownDegrees = ((orientation + 45) / 90 * 90) % 360
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun stop() {
        listener.disable()
    }

    /**
     * The MP4 `setOrientationHint` value (degrees, 0/90/180/270) for a recording started
     * right now with [cameraCharacteristics]'s rear lens, given the most recently observed
     * physical device rotation.
     *
     * This is the standard `getJpegOrientation()` formula from
     * `CameraCharacteristics.SENSOR_ORIENTATION`'s own documentation, simplified for a
     * rear-facing-only camera (this app never uses the front camera, so the
     * front-camera sign flip in the original sample is omitted) — video and still-photo
     * orientation hints use the same rotation math, only the API you hand the result to
     * differs (`MediaMuxer.setOrientationHint` vs `CaptureRequest.JPEG_ORIENTATION`).
     */
    fun orientationHintDegreesFor(cameraCharacteristics: CameraCharacteristics): Int {
        val sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return (sensorOrientation + lastKnownDegrees + 360) % 360
    }
}
