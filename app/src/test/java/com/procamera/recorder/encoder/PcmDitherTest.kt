package com.procamera.recorder.encoder

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class PcmDitherTest {

    @Test
    fun silence_producesOutputWithinDitherNoiseFloor() {
        val input = FloatArray(1000) { 0f }
        val output = ShortArray(1000)
        PcmDither.floatToInt16Tpdf(input, output, input.size, Random(42))
        // TPDF dither for a sum of two uniform[-0.5,0.5) is bounded in [-1, 1]; silence
        // input must never produce anything beyond that dither noise floor.
        for (sample in output) {
            assertThat(sample.toInt()).isIn(-1..1)
        }
    }

    @Test
    fun fullScalePositive_mapsNearShortMaxWithoutOverflowWraparound() {
        val input = FloatArray(100) { 1.0f }
        val output = ShortArray(100)
        PcmDither.floatToInt16Tpdf(input, output, input.size, Random(1))
        for (sample in output) {
            // Must clamp at Short.MAX_VALUE, never wrap around to negative.
            assertThat(sample.toInt()).isGreaterThan(32760)
            assertThat(sample.toInt()).isAtMost(Short.MAX_VALUE.toInt())
        }
    }

    @Test
    fun fullScaleNegative_mapsNearShortMinWithoutOverflowWraparound() {
        val input = FloatArray(100) { -1.0f }
        val output = ShortArray(100)
        PcmDither.floatToInt16Tpdf(input, output, input.size, Random(2))
        for (sample in output) {
            assertThat(sample.toInt()).isLessThan(-32760)
            assertThat(sample.toInt()).isAtLeast(Short.MIN_VALUE.toInt())
        }
    }

    @Test
    fun outOfRangeInput_isClampedNotWrapped() {
        // All these inputs clamp to +-1.0 before scaling, so after dither (+-1 noise
        // floor) the output must land within a couple LSBs of +-Short.MAX_VALUE — the
        // key property being "clamped near the ceiling/floor", not "wrapped to a
        // wildly different value" (e.g. a signed-overflow bug would wrap +32767ish to a
        // large negative number instead).
        val input = floatArrayOf(5.0f, -5.0f, 100.0f, -100.0f)
        val output = ShortArray(4)
        PcmDither.floatToInt16Tpdf(input, output, input.size, Random(3))
        assertThat(output[0].toInt()).isGreaterThan(32760)
        assertThat(output[1].toInt()).isLessThan(-32760)
        assertThat(output[2].toInt()).isGreaterThan(32760)
        assertThat(output[3].toInt()).isLessThan(-32760)
    }

    @Test
    fun ditherActuallyVariesOutput_forAConstantLowLevelSignal() {
        // A signal too quiet to register a full LSB step (e.g. 0.2 LSB) should, under
        // dithering, produce a MIX of adjacent quantized values across many samples
        // (rather than being truncated to the exact same value every time) — this is
        // the entire point of dithering: it turns quantization error into noise instead
        // of a static bias.
        val lsbFraction = 0.2f / 32767.0f
        val input = FloatArray(2000) { lsbFraction }
        val output = ShortArray(2000)
        PcmDither.floatToInt16Tpdf(input, output, input.size, Random(7))

        val distinctValues = output.toSet()
        assertThat(distinctValues.size).isGreaterThan(1)
    }

    @Test
    fun outputTooSmallForSampleCount_throws() {
        val input = FloatArray(10)
        val output = ShortArray(5)
        try {
            PcmDither.floatToInt16Tpdf(input, output, sampleCount = 10)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun sampleCountSmallerThanBuffers_onlyConvertsPrefix() {
        // The real bug this signature exists to prevent (see AudioEncoder's git history):
        // callers must be able to reuse fixed-size scratch buffers and convert only the
        // first sampleCount elements, with the rest of output left untouched.
        val input = FloatArray(10) { 1.0f }
        val output = ShortArray(10) { 999 }
        PcmDither.floatToInt16Tpdf(input, output, sampleCount = 4)
        for (i in 0 until 4) {
            assertThat(output[i].toInt()).isGreaterThan(32760)
        }
        for (i in 4 until 10) {
            assertThat(output[i].toInt()).isEqualTo(999)
        }
    }
}
