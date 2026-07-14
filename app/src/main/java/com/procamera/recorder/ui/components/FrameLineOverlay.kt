package com.procamera.recorder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Composition guide (§CameraUiState.FrameLineAspectRatio's doc): draws a bordered
 * rectangle at [targetAspectRatio] (width/height), centered within the preview, with the
 * area outside it dimmed. Purely a framing aid — the recorded file's aspect ratio is
 * untouched; this Canvas draws on top of the preview, nothing more.
 *
 * Deliberately does NOT append its own `.fillMaxSize()` — the caller must size this to
 * exactly the same rect as `PreviewSurfaceView` (same modifier chain, not just an
 * equivalent one), or the guide can drift from what's actually framed. See the call site
 * in MainScreen.kt.
 */
@Composable
fun FrameLineOverlay(targetAspectRatio: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val previewAspect = w / h

        val guideW: Float
        val guideH: Float
        if (targetAspectRatio > previewAspect) {
            // Guide rectangle is relatively wider than the preview -> width-constrained.
            guideW = w
            guideH = w / targetAspectRatio
        } else {
            guideW = h * targetAspectRatio
            guideH = h
        }
        val left = (w - guideW) / 2f
        val top = (h - guideH) / 2f

        val scrimColor = Color.Black.copy(alpha = 0.55f)
        if (top > 0f) {
            drawRect(color = scrimColor, topLeft = Offset(0f, 0f), size = Size(w, top))
            drawRect(color = scrimColor, topLeft = Offset(0f, top + guideH), size = Size(w, h - top - guideH))
        }
        if (left > 0f) {
            drawRect(color = scrimColor, topLeft = Offset(0f, top), size = Size(left, guideH))
            drawRect(color = scrimColor, topLeft = Offset(left + guideW, top), size = Size(w - left - guideW, guideH))
        }

        drawRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = Offset(left, top),
            size = Size(guideW, guideH),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}
