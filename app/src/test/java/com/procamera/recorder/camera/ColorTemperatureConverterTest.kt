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
    fun greenGainIsAlwaysPinnedToOne_acrossTheWholeRange() {
        // This model always treats green as the neutral/reference channel (see class doc).
        for (kelvin in 2500..8000 step 250) {
            val (_, greenGain, _) = ColorTemperatureConverter.kelvinToRgbGains(kelvin.toDouble())
            assertThat(greenGain).isEqualTo(1.0f)
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
    fun rgbGainsToKelvin_roundTripsThroughKelvinToRgbGains() {
        // The inverse function exists to surface the ISP's Auto-AWB measured gains as a
        // Kelvin value in the UI — it must actually invert the forward function for that
        // UI value to be meaningful.
        for (kelvin in 2500..8000 step 250) {
            val (redGain, _, blueGain) = ColorTemperatureConverter.kelvinToRgbGains(kelvin.toDouble())
            val roundTripped = ColorTemperatureConverter.rgbGainsToKelvin(redGain, blueGain)
            assertThat(roundTripped).isWithin(1.0).of(kelvin.toDouble())
        }
    }

    @Test
    fun rgbGainsToKelvin_isClampedToTheSpecRange() {
        val kelvin = ColorTemperatureConverter.rgbGainsToKelvin(red = 10f, blue = 0.1f)
        assertThat(kelvin).isAtMost(8000.0)
        assertThat(kelvin).isAtLeast(2500.0)
    }
}
