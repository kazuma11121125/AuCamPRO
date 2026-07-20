package com.aucampro.recorder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aucampro.recorder.ui.theme.Amber
import com.aucampro.recorder.ui.theme.OnSurfacePrimary
import com.aucampro.recorder.ui.theme.OnSurfaceSecondary
import com.aucampro.recorder.ui.theme.SliderTrackInactive
import kotlin.math.roundToInt

/**
 * Labelled manual-control slider for camera parameters (§4.5).
 *
 * Shows:
 * ```
 *  LABEL               value text
 *  [─────────────●─────────────]
 * ```
 *
 * @param label         Short uppercase label ("ISO", "SHUTTER", "FOCUS", "WB").
 * @param value         Current slider position in [0, 1] (normalised by caller).
 * @param valueText     Human-readable value to display on the right ("400", "1/60", "5.0m", "5500K").
 * @param onValueChange Callback with new normalised [0, 1] value.
 * @param enabled       Whether the slider is interactive (e.g. WB disabled on LIMITED cameras).
 */
@Composable
fun ManualControlSlider(
    label: String,
    value: Float,
    valueText: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.0.sp,
                color = if (enabled) OnSurfaceSecondary else OnSurfaceSecondary.copy(alpha = 0.4f),
            )
            Text(
                text = valueText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) OnSurfacePrimary else OnSurfacePrimary.copy(alpha = 0.35f),
                textAlign = TextAlign.End,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = SliderTrackInactive,
                disabledThumbColor = SliderTrackInactive,
                disabledActiveTrackColor = SliderTrackInactive,
                disabledInactiveTrackColor = SliderTrackInactive.copy(alpha = 0.4f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Typed convenience wrappers
// ──────────────────────────────────────────────────────────────────────────────

/**
 * ISO slider. Maps [isoRange] to [0, 1] linearly on a log scale (ISO is perceptually
 * uniform on a log scale — doubling ISO = one EV).
 */
@Composable
fun IsoSlider(
    iso: Int,
    isoRange: IntRange,
    onIsoChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val logMin = kotlin.math.ln(isoRange.first.toFloat())
    val logMax = kotlin.math.ln(isoRange.last.toFloat())
    val normalized = ((kotlin.math.ln(iso.toFloat()) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)

    ManualControlSlider(
        label = "ISO",
        value = normalized,
        valueText = iso.toString(),
        onValueChange = { norm ->
            val logVal = logMin + norm * (logMax - logMin)
            val rawIso = kotlin.math.exp(logVal).roundToInt()
            // Snap to common ISO values for a more camera-like feel.
            val snapped = snapToCommonIso(rawIso, isoRange)
            onIsoChange(snapped)
        },
        modifier = modifier,
        enabled = enabled,
    )
}

/** Snaps to the nearest common ISO stop within [isoRange]. */
private fun snapToCommonIso(raw: Int, range: IntRange): Int {
    val commonIsos = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
        .filter { it in range }
    if (commonIsos.isEmpty()) return raw.coerceIn(range)
    return commonIsos.minByOrNull { kotlin.math.abs(it - raw) } ?: raw
}

/**
 * Shutter speed slider. Operates in log(exposure time) space for perceptual linearity;
 * snap points correspond to the §4.1 LED-PWM presets (1/50, 1/60, 1/100, 1/120) and
 * common photographic values.
 *
 * @param exposureTimeNanos Current exposure time in nanoseconds.
 * @param rangeNanos        Device-reported [min, max] exposure range.
 * @param onValueChange     Returns new exposure time in nanoseconds.
 */
@Composable
fun ShutterSlider(
    exposureTimeNanos: Long,
    rangeNanos: LongRange,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val logMin = kotlin.math.ln(rangeNanos.first.toDouble())
    val logMax = kotlin.math.ln(rangeNanos.last.toDouble())
    val normalized = ((kotlin.math.ln(exposureTimeNanos.toDouble()) - logMin) / (logMax - logMin))
        .toFloat().coerceIn(0f, 1f)

    val displayText = formatExposureTime(exposureTimeNanos)

    ManualControlSlider(
        label = "SHUTTER",
        value = normalized,
        valueText = displayText,
        onValueChange = { norm ->
            val logVal = logMin + norm * (logMax - logMin)
            val rawNanos = kotlin.math.exp(logVal).toLong()
            onValueChange(rawNanos.coerceIn(rangeNanos))
        },
        modifier = modifier,
        enabled = enabled,
    )
}

private fun formatExposureTime(nanos: Long): String {
    if (nanos >= 1_000_000_000L) return "%.1fs".format(nanos / 1_000_000_000.0)
    val denominator = (1_000_000_000.0 / nanos).roundToInt()
    return "1/$denominator"
}

/**
 * Focus distance slider. 0 diopters = infinity; [minFocusDistance] = nearest focus.
 * The slider moves left→infinity, right→nearest for a natural "pull focus" feel.
 */
@Composable
fun FocusSlider(
    focusDiopters: Float,
    minFocusDistance: Float,
    onFocusChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Normalise: 0 (left) = infinity (0 diopters), 1 (right) = nearest (minFocusDistance)
    val normalized = if (minFocusDistance > 0f) (focusDiopters / minFocusDistance).coerceIn(0f, 1f) else 0f

    val displayText = if (focusDiopters == 0f) "∞" else {
        val meters = 1f / focusDiopters
        if (meters >= 10f) "%.0fm".format(meters) else "%.1fm".format(meters)
    }

    ManualControlSlider(
        label = "FOCUS",
        value = normalized,
        valueText = displayText,
        onValueChange = { norm -> onFocusChange(norm * minFocusDistance) },
        modifier = modifier,
    )
}

/**
 * White balance slider. Maps 2500–8000 K linearly (Kelvin scale is already approximately
 * perceptually uniform for the hue shifts it covers in this range).
 */
@Composable
fun WhiteBalanceSlider(
    kelvin: Double,
    enabled: Boolean,
    onKelvinChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    kelvinMin: Double = 2_500.0,
    kelvinMax: Double = 8_000.0,
) {
    val normalized = ((kelvin - kelvinMin) / (kelvinMax - kelvinMin)).toFloat().coerceIn(0f, 1f)

    ManualControlSlider(
        label = "WB",
        value = normalized,
        valueText = "${kelvin.roundToInt()}K",
        onValueChange = { norm ->
            onKelvinChange(kelvinMin + norm * (kelvinMax - kelvinMin))
        },
        enabled = enabled,
        modifier = modifier,
    )
}
