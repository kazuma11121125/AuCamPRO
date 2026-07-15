package com.procamera.recorder.ui.components

import android.Manifest
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.procamera.recorder.ui.viewmodel.CameraControlViewModel

/**
 * Compose wrapper around a [SurfaceView] that serves as the camera preview surface.
 *
 * Camera2 streams frames directly to the [SurfaceHolder.getSurface] of this view, so the
 * preview is zero-copy from the HAL — exactly [§4.1]'s "independent path" requirement for
 * the preview stream (the recording encoder stream uses a separate MediaCodec InputSurface
 * added to the same Camera2 session but not rendered here).
 *
 * Surface lifecycle:
 * - [SurfaceHolder.Callback.surfaceCreated]: calls [CameraControlViewModel.attachPreviewSurface]
 *   so the ViewModel can open the camera session with this surface.
 * - [SurfaceHolder.Callback.surfaceDestroyed]: calls [CameraControlViewModel.detachPreviewSurface]
 *   so the ViewModel can clean up before the Surface is invalidated.
 *
 * The [CAMERA] permission must have been granted by the caller before this composable is
 * included in the hierarchy (MainActivity / MainScreen manages the permission flow).
 *
 * @param viewModel The ViewModel that owns the [RecordingPipeline].
 * @param aspectRatio Width / height of the camera sensor output in the current orientation
 *   (e.g. 16/9 for 4K HEVC in landscape — but note the camera may be physically rotated
 *   90° relative to the sensor, so pass `9f/16f` for a portrait-mounted phone with a
 *   landscape sensor). Default `9f/16f` matches most portrait-orientation phone cameras.
 *   Purely a Compose layout hint for sizing the container box — see [bufferWidth]/
 *   [bufferHeight] for what actually controls the delivered pixel content.
 * @param bufferWidth/[bufferHeight] The exact pixel size to pin this Surface's buffer
 *   producer to via [SurfaceHolder.setFixedSize], matching [aspectRatio]. Without this,
 *   Camera2 is free to configure the preview stream at whatever size the SurfaceHolder
 *   defaults to — confirmed on real hardware (Sony SO-51C) to silently be a square
 *   1080x1080 buffer regardless of the requested video config or device orientation, which
 *   SurfaceFlinger's compositor then non-uniformly stretches to fill this composable's
 *   (correctly-shaped) layout box. Must be set before [SurfaceHolder.Callback.surfaceCreated]
 *   hands the surface to Camera2, so this take effect on the very first frame.
 * @param modifier Modifier for the outer layout.
 * @param onTap Plain tap (short press) on the preview — MainScreen wires this to
 *   [CameraControlViewModel.toggleControls]. Combined with [onLongPressToFocus] in a
 *   single `detectTapGestures` call (rather than a separate `Modifier.clickable`
 *   elsewhere) so the two gestures can't race/double-fire against each other.
 * @param onLongPressToFocus Long-press-to-focus (§AF/MFモード) — [normalizedX]/
 *   [normalizedY] are [0,1] coordinates within *this composable's own rendered bounds*
 *   (the `PointerInputScope.size` this gesture detector sees, which is exactly the
 *   aspect-ratio-constrained `SurfaceView`'s box, not the wider outer preview area it
 *   may sit inside) — see [com.procamera.recorder.camera.TapToMeteringRegion]'s doc for
 *   why that's the contract the receiving end expects.
 */
@androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
@Composable
fun PreviewSurfaceView(
    viewModel: CameraControlViewModel,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 9f / 16f, // portrait phone with landscape-sensor camera (16:9 → 9:16)
    bufferWidth: Int = 1080,
    bufferHeight: Int = 1920,
    onTap: () -> Unit = {},
    onLongPressToFocus: (normalizedX: Float, normalizedY: Float) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current

    // SurfaceView is created once and reused across recompositions.
    val surfaceView = remember { SurfaceView(context) }

    DisposableEffect(surfaceView) {
        surfaceView.holder.setFixedSize(bufferWidth, bufferHeight)
        val callback = object : SurfaceHolder.Callback {
            @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
            override fun surfaceCreated(holder: SurfaceHolder) {
                viewModel.attachPreviewSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Camera2 handles resolution; we don't need to do anything here.
            }

            @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                viewModel.detachPreviewSurface()
            }
        }
        surfaceView.holder.addCallback(callback)
        onDispose {
            surfaceView.holder.removeCallback(callback)
        }
    }

    // Re-pin whenever the intended buffer size changes (resolution setting change, or
    // orientation change while the surface is already alive) — surfaceCreated only fires
    // once per Surface lifetime, but setFixedSize() can be called again on a live holder to
    // reconfigure the producer for subsequent frames.
    androidx.compose.runtime.LaunchedEffect(bufferWidth, bufferHeight) {
        surfaceView.holder.setFixedSize(bufferWidth, bufferHeight)
    }

    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    AndroidView(
        factory = { surfaceView },
        modifier = modifier
            .aspectRatio(aspectRatio, matchHeightConstraintsFirst = isLandscape)
            .pointerInput(onTap, onLongPressToFocus) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { offset ->
                        onLongPressToFocus(offset.x / size.width, offset.y / size.height)
                    },
                )
            },
    )
}
