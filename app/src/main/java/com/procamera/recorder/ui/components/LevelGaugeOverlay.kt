package com.procamera.recorder.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procamera.recorder.ui.theme.MeterGreen
import com.procamera.recorder.ui.theme.OnSurfacePrimary
import kotlin.math.abs

private const val LEVEL_THRESHOLD_DEGREES = 1.5f

/**
 * Buckets [OrientationEventListener]'s 0-359° continuous reading (no range-limit problem,
 * unlike `SensorManager.getOrientation()`'s roll component — see class doc's "real-device
 * bug" note) into the nearest quadrant, then maps to the matching `Surface.ROTATION_*` for
 * [SensorManager.remapCoordinateSystem]. This is the standard mapping (the same one AndroidX
 * CameraX's own orientation-tracking uses): the device's physical rotation and the
 * corresponding *content* rotation needed to stay upright run in opposite directions.
 */
private fun physicalRotationFrom(orientationDegrees: Int): Int = when (orientationDegrees) {
    in 45..134 -> Surface.ROTATION_270
    in 135..224 -> Surface.ROTATION_180
    in 225..314 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0
}

/**
 * Roll-angle spirit level (Sony Photo Pro/Video Pro's 水準器/レベルメーター — see
 * Sony__________.pdf: "ジャイロセンサーを利用して水平を検知し緑色に点灯するレベルメーター
 * (水準器)"). A horizon line rotates opposite the phone's roll so it visually stays aligned
 * with the true horizon against a fixed center tick, turning green within
 * [LEVEL_THRESHOLD_DEGREES] of level.
 *
 * Uses `TYPE_ROTATION_VECTOR` (sensor-fused, drift-corrected) rather than raw accelerometer
 * — far less jittery for a live overlay.
 *
 * **Physical orientation, not window orientation**: [SensorManager.remapCoordinateSystem]
 * needs to know which edge of the device is currently "up" to report a camera-relevant
 * roll (bank around the lens axis) rather than the raw azimuth/pitch/roll triple. The
 * obvious source for that is `Display.rotation` — but this app's window is locked to
 * `sensorLandscape` (real-device feedback: the UI itself should stay landscape-only), which
 * means `Display.rotation` never changes even when the user physically rotates the phone to
 * shoot portrait. Using it here would make the gauge silently wrong for exactly the
 * portrait-hold case it's supposed to help with.
 *
 * **実機で発見・修正**: the first attempt derived physical orientation from
 * `SensorManager.getOrientation()`'s own "roll" component (its `values[2]`) — but that
 * value is range-limited to **±90°** by definition (unlike pitch, which spans the full
 * ±180°), so a bucketing scheme built to distinguish four 90°-apart quadrants against it
 * (assuming it swung the full ±180°) was checking thresholds the value could never actually
 * reach — confirmed on real hardware: held in portrait, the gauge read a false "level" (0°)
 * instead of reflecting the true tilt. [OrientationEventListener] doesn't have this problem
 * (a continuous, unbounded-range 0-359° reading, purpose-built for exactly this "which way
 * is the phone physically held" question — see [physicalRotationFrom]) and now supplies the
 * coarse quadrant; `TYPE_ROTATION_VECTOR` still supplies the fine-grained, smooth roll
 * within that quadrant via the same remap-and-read-roll approach as before.
 */
@Composable
fun LevelGaugeOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var rollDegrees by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        var physicalRotation = Surface.ROTATION_0
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                physicalRotation = physicalRotationFrom(orientation)
            }
        }

        val rotationVectorListener = object : SensorEventListener {
            private val rawRotationMatrix = FloatArray(9)
            private val remappedMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)

                val (axisX, axisY) = when (physicalRotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rawRotationMatrix, axisX, axisY, remappedMatrix)
                SensorManager.getOrientation(remappedMatrix, orientation)
                // orientation[2] = roll (radians). Positive here means the device's right
                // edge (in whichever physical orientation it's currently held) is tilted
                // down.
                //
                // **実機で発見(2026-07-15)**: an earlier attempt added a ±90° offset here
                // for portrait hold (ROTATION_0/180), on the theory that the portrait
                // "identity" remap measures roll around the wrong axis. Real-device
                // feedback (2026-07-15, later the same day) confirmed that offset was
                // itself wrong — held level in portrait, the gauge read ~-90° (i.e. the
                // offset was *introducing* the error, not correcting one). Removed;
                // portrait now uses the same raw remapped value as landscape.
                rollDegrees = Math.toDegrees(orientation[2].toDouble()).toFloat()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (rotationSensor != null) {
            sensorManager.registerListener(rotationVectorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            orientationListener.disable()
            sensorManager.unregisterListener(rotationVectorListener)
        }
    }

    val isLevel = abs(rollDegrees) < LEVEL_THRESHOLD_DEGREES
    val lineColor = if (isLevel) MeterGreen else Color.White

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.size(width = 120.dp, height = 24.dp)) {
            // Fixed center reference tick — drawn unrotated; the horizon line below must
            // visually align with this to read "level".
            drawLine(
                color = Color.White.copy(alpha = 0.6f),
                start = Offset(size.width / 2f, size.height / 2f - 6.dp.toPx()),
                end = Offset(size.width / 2f, size.height / 2f + 6.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
            )
            rotate(degrees = -rollDegrees, pivot = center) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }
        Text(
            text = "%+.1f°".format(rollDegrees),
            color = if (isLevel) MeterGreen else OnSurfacePrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
