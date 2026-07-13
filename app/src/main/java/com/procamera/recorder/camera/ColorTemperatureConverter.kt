package com.procamera.recorder.camera

import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

/**
 * Converts a color temperature in Kelvin (§4.1 Manual WB, 2500K-8000K) into the RGGB
 * sensor gains Camera2 expects for `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` +
 * `COLOR_CORRECTION_GAINS`.
 *
 * **確信度の明示**: This uses Tanner Helland's public-domain black-body-radiation RGB
 * approximation (a widely used, but not colorimetrically exact, polynomial fit — it is
 * not derived from the CIE standard observer / Planckian locus directly). It is a
 * reasonable starting point for a manual WB slider, but the actual color science pipeline
 * (sensor color filter response, ISP-specific rendering intent) varies per device and
 * cannot be validated without real-hardware comparison against a reference target (e.g. a
 * grey card shot under known-Kelvin lighting). Treat the resulting gains as a good first
 * approximation that a Phase 5 real-device check should visually verify, not as a
 * calibrated color-accurate mapping.
 */
object ColorTemperatureConverter {

    private const val MIN_KELVIN = 2500.0
    private const val MAX_KELVIN = 8000.0

    /**
     * @param kelvin clamped to [MIN_KELVIN, MAX_KELVIN] (§4.1's specified UI range).
     * @return RGGB gains normalized so every channel is >= 1.0 (the channel matching the
     *   illuminant's strongest raw-sensor response gets exactly 1.0; the others are
     *   boosted above it). This is the HAL-safe convention: `COLOR_CORRECTION_GAINS` gains
     *   are commonly required to be >= 1.0 (the neutral channel sits at 1.0, others boost
     *   above it), and a naive green-pinned-to-1.0 normalization can produce sub-unity
     *   gains at the range extremes that a HAL may clamp or reject. Pinning to the
     *   *minimum* instead of green preserves the exact same relative channel ratios
     *   (color correction only depends on ratios, not absolute scale), so this is a
     *   rescaling, not a different correction — see [kelvinToRgbGains]'s derivation.
     */
    fun kelvinToRggbGains(kelvin: Double): RggbChannelVector {
        val (redGain, greenGain, blueGain) = kelvinToRgbGains(kelvin)
        return RggbChannelVector(redGain, greenGain, greenGain, blueGain)
    }

    /**
     * Pure-Kotlin core of [kelvinToRggbGains], factored out so it can be unit-tested
     * without touching android.hardware.camera2.params.RggbChannelVector (a framework
     * class that plain JUnit host tests cannot construct without Robolectric — see
     * ColorTemperatureConverterTest).
     */
    fun kelvinToRgbGains(kelvin: Double): Triple<Float, Float, Float> {
        val clamped = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN)
        val (r, g, b) = blackBodyRgb(clamped)
        // Correction gains are proportional to the INVERSE of the illuminant's own
        // apparent color (1/r, 1/g, 1/b) — a warm/low-Kelvin illuminant appears
        // red-heavy, so correcting it toward white means relatively attenuating red and
        // boosting blue. We then rescale so the MINIMUM of the three inverse values maps
        // to exactly 1.0, i.e. multiply every gain by max(r,g,b): the channel that was
        // strongest in the raw data needs no boost (gain=1.0), the others are boosted
        // above it in the same ratio a green-pinned normalization would have used, just
        // without ever going below 1.0.
        val maxChannel = maxOf(r, g, b)
        return Triple((maxChannel / r).toFloat(), (maxChannel / g).toFloat(), (maxChannel / b).toFloat())
    }

    /**
     * Tanner Helland's approximation (https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm.html),
     * operating on temperature/100 in the 1000K-40000K domain. Returns un-normalized RGB
     * in [0, 255] (this function only cares about their *ratios*, so the absolute scale
     * doesn't matter to the caller).
     */
    private fun blackBodyRgb(kelvin: Double): Triple<Double, Double, Double> {
        val t = kelvin / 100.0

        val red = if (t <= 66.0) {
            255.0
        } else {
            (329.698727446 * (t - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        }

        val green = if (t <= 66.0) {
            (99.4708025861 * ln(t) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (t - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }

        val blue = when {
            t >= 66.0 -> 255.0
            t <= 19.0 -> 0.0
            else -> (138.5177312231 * ln(t - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        // Guard against the degenerate case (shouldn't happen within our 2500-8000K
        // range, but any channel passing through 0 would make the normalization above
        // divide by zero).
        val safeGreen = if (green <= 0.0) 1.0 else green
        val safeBlue = if (blue <= 0.0) 1.0 else blue
        return Triple(red, safeGreen, safeBlue)
    }
}
