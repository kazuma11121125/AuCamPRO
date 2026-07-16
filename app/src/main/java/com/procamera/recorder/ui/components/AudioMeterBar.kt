package com.procamera.recorder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procamera.recorder.ui.theme.MeterGreen
import com.procamera.recorder.ui.theme.MeterOrange
import com.procamera.recorder.ui.theme.MeterRed
import com.procamera.recorder.ui.theme.MeterYellow
import com.procamera.recorder.ui.theme.OnSurfacePrimary
import com.procamera.recorder.ui.theme.OnSurfaceSecondary
import com.procamera.recorder.ui.theme.SurfaceBlack
import com.procamera.recorder.ui.theme.SurfaceVariant

/**
 * Segmented (LED-bridge style) vertical audio level meter (§4.5), styled after a field
 * production audio bridge rather than a single smooth bar: discrete lit/unlit blocks
 * (broadcast dBFS colour zones: green ≤−12, yellow ≤−6, orange/red above), a numeric peak
 * dBFS readout above the bar, and a peak-hold highlighted segment. Drawn entirely with
 * Canvas for minimal overhead at the ~60fps update rate driven by
 * [CameraControlViewModel]'s meter polling.
 *
 * Sizing is entirely caller-driven via [modifier] (this composable is only ever used
 * inside [StereoAudioMeter], which supplies a weighted width + fillMaxHeight) — there is
 * deliberately no internal default width/height here, to avoid two competing size
 * constraints.
 *
 * @param peakDb   Instantaneous peak in dBFS (−120 = silence sentinel).
 * @param rmsDb    Short-term RMS in dBFS — drives the lit segment count.
 * @param isClippingHeld  True when within the 3s clipping hold window (§4.5).
 */
@Composable
fun AudioMeterBar(
    peakDb: Float,
    rmsDb: Float,
    isClippingHeld: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (peakDb <= DB_FLOOR) "···" else formatDb(peakDb),
            color = if (isClippingHeld) MeterRed else OnSurfacePrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .clip(RoundedCornerShape(2.dp))
                .background(SurfaceBlack)
                .border(1.dp, SurfaceVariant, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                drawSegmentedMeter(peakDb, rmsDb)
            }
            if (isClippingHeld) {
                Text(
                    text = "CLIP",
                    color = Color.White,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(MeterRed)
                        .padding(horizontal = 2.dp),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Two-channel (stereo) field-style meter bridge, with a shared dB scale between the bars.
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
    Column(
        // Fixed (not min-only) width: the inner Row below uses weight() to split the two
        // bars evenly, which needs a bounded width from its parent chain. This composable's
        // own caller (MainScreen) wraps it in a Row with no explicit width of its own (just
        // wrap-content) — leaving this at widthIn(min=...) let that ambiguity propagate
        // down to the weight()s and the whole meter silently collapsed to zero size
        // (observed on-device: no crash, just nothing drawn). A fixed width here breaks
        // that ambiguity regardless of how the caller sizes its wrapper.
        modifier = modifier
            .fillMaxHeight()
            .width(84.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, OnSurfaceSecondary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = true).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AudioMeterBar(
                peakDb = peakDbL, rmsDb = rmsDbL, isClippingHeld = isClippingHeldL,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            DbScale(modifier = Modifier.fillMaxHeight().width(18.dp).padding(top = 15.dp))
            AudioMeterBar(
                peakDb = peakDbR, rmsDb = rmsDbR, isClippingHeld = isClippingHeldR,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("L", color = OnSurfaceSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("R", color = OnSurfaceSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private val TickDbs = listOf(0f, -6f, -12f, -24f, -48f)

/**
 * Numeric dB scale shared between the L/R bars, mirroring a hardware meter bridge's centre
 * scale.
 *
 * **実機で発見**: this used to allocate a fresh `List` and `android.graphics.Paint` on
 * every single Canvas draw — at the meter's ~30Hz update rate, that's 30+ allocations/sec
 * purely for tick-label drawing, which measurably adds to GC pressure shared with the
 * video/audio encoder threads (see [com.procamera.recorder.encoder.VideoEncoder]'s
 * `BufferInfo` reuse doc for the same finding on the encoder side). [TickDbs] is now a
 * module-level constant and [paint] is created once via `remember` and only mutated
 * (never reallocated) per draw.
 */
@Composable
private fun DbScale(modifier: Modifier = Modifier) {
    val paint = androidx.compose.runtime.remember {
        android.graphics.Paint().apply {
            color = OnSurfaceSecondary.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    Canvas(modifier = modifier) {
        paint.textSize = 8.sp.toPx()
        for (tickDb in TickDbs) {
            val y = size.height * (1f - dbToFraction(tickDb))
            drawContext.canvas.nativeCanvas.drawText(
                if (tickDb == 0f) "0" else tickDb.toInt().toString(),
                size.width / 2f,
                y + 3.dp.toPx(),
                paint,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Canvas drawing helpers
// ──────────────────────────────────────────────────────────────────────────────

/**
 * One-decimal-place formatting without `String.format`/`java.util.Formatter`'s per-call
 * `Locale`/`Formatter` allocation — called on every peak-dB label update (~30Hz), same
 * GC-pressure concern as [DbScale]'s doc.
 */
private fun formatDb(db: Float): String {
    val tenths = Math.round(db * 10)
    val sign = if (tenths < 0) "-" else ""
    val absTenths = kotlin.math.abs(tenths)
    return "$sign${absTenths / 10}.${absTenths % 10}"
}

private const val DB_FLOOR = -60f      // lowest dBFS displayed
private const val DB_CEIL = 0f         // top of meter (0 dBFS)
private const val SEGMENT_COUNT = 24   // discrete LED-style blocks, ~2.5dB each
private const val SEGMENT_GAP_FRACTION = 0.18f // fraction of each segment's height left as a gap

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

/** Unlit segment colour — a faint tint of the zone's own colour, not a flat grey, so the
 * scale's colour structure is legible even at rest (matching a hardware LED bridge). */
private fun unlitSegmentColor(db: Float): Color = meterColor(db).copy(alpha = 0.12f)

private fun DrawScope.drawSegmentedMeter(peakDb: Float, rmsDb: Float) {
    val w = size.width
    val h = size.height
    val segmentH = h / SEGMENT_COUNT
    val gap = segmentH * SEGMENT_GAP_FRACTION
    val litFraction = dbToFraction(rmsDb)
    val litSegments = (litFraction * SEGMENT_COUNT).toInt()
    val peakSegment = if (peakDb > DB_FLOOR) (dbToFraction(peakDb) * SEGMENT_COUNT).toInt().coerceIn(0, SEGMENT_COUNT - 1) else -1

    for (i in 0 until SEGMENT_COUNT) {
        val segMinDb = DB_FLOOR + (DB_CEIL - DB_FLOOR) * i / SEGMENT_COUNT
        val segTop = h - (i + 1) * segmentH
        val color = if (i < litSegments || i == peakSegment) meterColor(segMinDb) else unlitSegmentColor(segMinDb)
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, segTop + gap / 2f),
            size = Size(w, segmentH - gap),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
    }
}
