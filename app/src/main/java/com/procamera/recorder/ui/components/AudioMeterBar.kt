package com.procamera.recorder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procamera.recorder.ui.theme.MeterGreen
import com.procamera.recorder.ui.theme.MeterOrange
import com.procamera.recorder.ui.theme.MeterRed
import com.procamera.recorder.ui.theme.MeterYellow
import com.procamera.recorder.ui.theme.OnSurfaceSecondary
import com.procamera.recorder.ui.theme.RecRed
import com.procamera.recorder.ui.theme.SurfaceVariant

/**
 * Vertical audio level meter (§4.5) drawn entirely with Canvas for minimal overhead
 * at the ~60fps update rate driven by [CameraControlViewModel]'s meter polling coroutine.
 *
 * dBFS colour zones (matching broadcast convention):
 * ```
 *   > −6 dBFS   Orange
 *   > −12 dBFS  Yellow
 *   ≤ −12 dBFS  Green
 *   Peak hold line at the highest recent level
 * ```
 *
 * The CLIPPING badge (−0.1 dBFS trigger, 3s hold) is overlaid as a Text composable above
 * the meter bar rather than drawn in Canvas to use Material3 typography.
 *
 * @param peakDb   Instantaneous peak in dBFS (−120 = silence sentinel).
 * @param rmsDb    Short-term RMS in dBFS.
 * @param isClippingHeld  True when within the 3s clipping hold window (§4.5).
 * @param width    Width of the single-bar meter column.
 */
@Composable
fun AudioMeterBar(
    peakDb: Float,
    rmsDb: Float,
    isClippingHeld: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = 14.dp,
) {
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawMeterBar(peakDb, rmsDb)
        }

        if (isClippingHeld) {
            Text(
                text = "CLIP",
                color = RecRed,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Two-channel (stereo) meter side-by-side
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun StereoAudioMeter(
    peakDbL: Float,
    peakDbR: Float,
    rmsDbL: Float,
    rmsDbR: Float,
    isClippingHeldL: Boolean,
    isClippingHeldR: Boolean,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        AudioMeterBar(
            peakDb = peakDbL,
            rmsDb = rmsDbL,
            isClippingHeld = isClippingHeldL,
        )
        AudioMeterBar(
            peakDb = peakDbR,
            rmsDb = rmsDbR,
            isClippingHeld = isClippingHeldR,
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Canvas drawing helpers
// ──────────────────────────────────────────────────────────────────────────────

private const val DB_FLOOR = -60f      // lowest dBFS displayed
private const val DB_CEIL = 0f         // top of meter (0 dBFS)

/** Maps a dBFS value to a fraction [0, 1] where 0 = silence and 1 = full scale. */
private fun dbToFraction(db: Float): Float {
    if (db <= DB_FLOOR) return 0f
    if (db >= DB_CEIL) return 1f
    return (db - DB_FLOOR) / (DB_CEIL - DB_FLOOR)
}

/** Returns the segment colour for a given dBFS level, matching broadcast VU zones. */
private fun meterColor(db: Float): Color = when {
    db > -6f -> MeterOrange
    db > -12f -> MeterYellow
    else -> MeterGreen
}

private fun DrawScope.drawMeterBar(peakDb: Float, rmsDb: Float) {
    val w = size.width
    val h = size.height
    val cornerRadius = CornerRadius(2.dp.toPx())

    // Background track
    drawRoundRect(
        color = SurfaceVariant,
        topLeft = Offset.Zero,
        size = Size(w, h),
        cornerRadius = cornerRadius,
    )

    // RMS fill (bottom-anchored, segmented by dB zone)
    val rmsTop = h * (1f - dbToFraction(rmsDb))
    if (rmsDb > DB_FLOOR) {
        // Draw three colour segments: green up to -12, yellow -12 to -6, orange -6 to 0.
        // We clip each segment to the actual rms level so the bar is segmented by colour.
        drawSegment(rmsTop, h, w, -60f, -12f, MeterGreen, cornerRadius)
        drawSegment(rmsTop, h, w, -12f, -6f, MeterYellow, cornerRadius)
        drawSegment(rmsTop, h, w, -6f, 0f, MeterOrange, cornerRadius)
    }

    // Peak hold tick mark (1px line in red when near clip, else white)
    if (peakDb > DB_FLOOR) {
        val peakY = h * (1f - dbToFraction(peakDb))
        val tickColor = if (peakDb > -6f) MeterRed else Color.White.copy(alpha = 0.7f)
        drawLine(
            color = tickColor,
            start = Offset(0f, peakY),
            end = Offset(w, peakY),
            strokeWidth = 2.dp.toPx(),
        )
    }

    // dB scale ticks (−48, −36, −24, −12, −6)
    val tickDbs = listOf(-48f, -36f, -24f, -12f, -6f)
    for (tickDb in tickDbs) {
        val tickY = h * (1f - dbToFraction(tickDb))
        drawLine(
            color = OnSurfaceSecondary.copy(alpha = 0.35f),
            start = Offset(w * 0.6f, tickY),
            end = Offset(w, tickY),
            strokeWidth = 0.5.dp.toPx(),
        )
    }
}

/**
 * Draws the portion of a colour zone that is below the [rmsTop] cutoff.
 *
 * @param rmsTop    Y-coordinate (from top) of the actual RMS level.
 * @param totalH    Total height of the meter.
 * @param zoneMinDb Lower dBFS bound of this zone.
 * @param zoneMaxDb Upper dBFS bound of this zone.
 */
private fun DrawScope.drawSegment(
    rmsTop: Float,
    totalH: Float,
    totalW: Float,
    zoneMinDb: Float,
    zoneMaxDb: Float,
    color: Color,
    cornerRadius: CornerRadius,
) {
    val zoneTop = totalH * (1f - dbToFraction(zoneMaxDb))
    val zoneBottom = totalH * (1f - dbToFraction(zoneMinDb))
    val segTop = maxOf(rmsTop, zoneTop)
    val segBottom = minOf(totalH, zoneBottom)
    if (segTop >= segBottom) return
    drawRoundRect(
        color = color,
        topLeft = Offset(0f, segTop),
        size = Size(totalW, segBottom - segTop),
        cornerRadius = cornerRadius,
    )
}
