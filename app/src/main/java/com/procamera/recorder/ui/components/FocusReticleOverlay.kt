package com.procamera.recorder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.procamera.recorder.camera.FocusController
import com.procamera.recorder.ui.theme.MeterGreen
import com.procamera.recorder.ui.viewmodel.FocusIndicator

/**
 * §フォーカス位置表示 — a corner-bracket reticle at [FocusIndicator.normalizedX]/
 * [normalizedY], color-coded by [FocusIndicator.state]: white while scanning, green once
 * locked-and-focused, red if it locked without achieving focus (timed out or the scene
 * had nothing to focus on). Matches the bracket style real camera apps use for a focus
 * point rather than a plain box, so it doesn't get confused with [FrameLineOverlay]'s
 * composition guide.
 *
 * Same coordinate contract as [FrameLineOverlay] — the caller must size this to exactly
 * the same rect as `PreviewSurfaceView` (see that composable's doc / MainScreen's call
 * site), since [FocusIndicator]'s normalized coordinates are relative to that surface.
 */
@Composable
fun FocusReticleOverlay(indicator: FocusIndicator, modifier: Modifier = Modifier) {
    val color = when (indicator.state) {
        FocusController.FocusIndicatorState.Scanning -> Color.White
        FocusController.FocusIndicatorState.Locked -> MeterGreen
        FocusController.FocusIndicatorState.Failed -> Color(0xFFE05555)
    }

    Canvas(modifier = modifier) {
        val cx = indicator.normalizedX * size.width
        val cy = indicator.normalizedY * size.height
        val halfSize = 32.dp.toPx()
        val cornerLength = 10.dp.toPx()
        val strokeWidth = 2.dp.toPx()

        val left = cx - halfSize
        val right = cx + halfSize
        val top = cy - halfSize
        val bottom = cy + halfSize

        // Four independent corner brackets rather than a full rectangle — reads as a
        // focus point, not a crop/composition guide (see this file's own doc).
        listOf(
            // top-left
            Offset(left, top + cornerLength) to Offset(left, top),
            Offset(left, top) to Offset(left + cornerLength, top),
            // top-right
            Offset(right - cornerLength, top) to Offset(right, top),
            Offset(right, top) to Offset(right, top + cornerLength),
            // bottom-right
            Offset(right, bottom - cornerLength) to Offset(right, bottom),
            Offset(right, bottom) to Offset(right - cornerLength, bottom),
            // bottom-left
            Offset(left + cornerLength, bottom) to Offset(left, bottom),
            Offset(left, bottom) to Offset(left, bottom - cornerLength),
        ).forEach { (start, end) ->
            drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLine(
    color: Color,
    start: Offset,
    end: Offset,
    strokeWidth: Float,
) = drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
