package com.procamera.recorder.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColorTemperatureConverterTest {

    @Test
    fun warmLight_boostsBlueMoreThanRed() {
        // 2500K (tungsten-like, warm) illuminant appears red-heavy in raw sensor data,
        // so the correction must boost blue more than red to cancel that cast.
        val (redGain, _, blueGain) = ColorTemperatureConverter.kelvinToRgbGains(2500.0)
        assertThat(blueGain).isGreaterThan(redGain)
    }

    @Test
    fun coolLight_boostsRedMoreThanBlue() {
        // 8000K (shade/overcast-like, cool) illuminant appears blue-heavy, so the
        // correction must boost red more than blue.
        val (redGain, _, blueGain) = ColorTemperatureConverter.kelvinToRgbGains(8000.0)
        assertThat(redGain).isGreaterThan(blueGain)
    }

    @Test
    fun redGainIncreasesMonotonicallyAsKelvinIncreases() {
        val samples = (2500..8000 step 500).map { ColorTemperatureConverter.kelvinToRgbGains(it.toDouble()).first }
        for (i in 1 until samples.size) {
            assertThat(samples[i]).isAtLeast(samples[i - 1])
        }
    }

    @Test
    fun blueGainDecreasesMonotonicallyAsKelvinIncreases() {
        val samples = (2500..8000 step 500).map { ColorTemperatureConverter.kelvinToRgbGains(it.toDouble()).third }
        for (i in 1 until samples.size) {
            assertThat(samples[i]).isAtMost(samples[i - 1])
        }
    }

    @Test
    fun gainsAreClampedToTheSpecRange() {
        val belowRange = ColorTemperatureConverter.kelvinToRgbGains(1000.0)
        val atMin = ColorTemperatureConverter.kelvinToRgbGains(2500.0)
        assertThat(belowRange).isEqualTo(atMin)

        val aboveRange = ColorTemperatureConverter.kelvinToRgbGains(20000.0)
        val atMax = ColorTemperatureConverter.kelvinToRgbGains(8000.0)
        assertThat(aboveRange).isEqualTo(atMax)
    }

    @Test
    fun gainsAreFiniteAndPositiveAcrossTheWholeRange() {
        for (kelvin in 2500..8000 step 100) {
            val (redGain, greenGain, blueGain) = ColorTemperatureConverter.kelvinToRgbGains(kelvin.toDouble())
            for (gain in listOf(redGain, greenGain, blueGain)) {
                assertThat(gain.isFinite()).isTrue()
                assertThat(gain).isGreaterThan(0f)
            }
        }
    }

    @Test
    fun everyChannelGainIsAtLeastOne_acrossTheWholeRange() {
        // HAL-safety requirement (§4.1): COLOR_CORRECTION_GAINS conventionally expects
        // every channel >= 1.0 (neutral channel at 1.0, others boosted above it) — a
        // naive green-pinned-to-1.0 normalization would violate this at the range
        // extremes (see ARCHITECTURE.md's judgment log for the bug this test guards).
        for (kelvin in 2500..8000 step 100) {
            val (redGain, greenGain, blueGain) = ColorTemperatureConverter.kelvinToRgbGains(kelvin.toDouble())
            assertThat(redGain).isAtLeast(1.0f)
            assertThat(greenGain).isAtLeast(1.0f)
            assertThat(blueGain).isAtLeast(1.0f)
        }
    }

    @Test
    fun atLeastOneChannelIsExactlyOneAcrossTheWholeRange() {
        // The strongest raw channel should be pinned to exactly 1.0 (unboosted); the
        // others boost relative to it.
        for (kelvin in 2500..8000 step 250) {
            val (redGain, greenGain, blueGain) = ColorTemperatureConverter.kelvinToRgbGains(kelvin.toDouble())
            val minGain = minOf(redGain, greenGain, blueGain)
            assertThat(minGain).isWithin(1e-4f).of(1.0f)
        }
    }
}
