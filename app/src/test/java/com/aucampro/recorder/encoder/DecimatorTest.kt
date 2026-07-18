package com.aucampro.recorder.encoder

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

class DecimatorTest {

    private fun sineWave(sampleRateHz: Int, freqHz: Double, frameCount: Int, channelCount: Int): FloatArray {
        val out = FloatArray(frameCount * channelCount)
        for (frame in 0 until frameCount) {
            val sample = sin(2.0 * PI * freqHz * frame / sampleRateHz).toFloat()
            for (ch in 0 until channelCount) out[frame * channelCount + ch] = sample
        }
        return out
    }

    private fun rms(signal: FloatArray, startFrame: Int, frameCount: Int, channelCount: Int, channel: Int): Double {
        var sumSquares = 0.0
        for (frame in startFrame until startFrame + frameCount) {
            val s = signal[frame * channelCount + channel].toDouble()
            sumSquares += s * s
        }
        return sqrt(sumSquares / frameCount)
    }

    @Test
    fun factorOne_isExactPassthrough() {
        val decimator = Decimator(inputSampleRateHz = 48000, outputSampleRateHz = 48000, channelCount = 2)
        val input = sineWave(48000, 1000.0, 512, 2)
        val output = FloatArray(input.size)
        val outFrames = decimator.process(input, 512, output)
        assertThat(outFrames).isEqualTo(512)
        assertThat(output).isEqualTo(input)
    }

    @Test
    fun twoToOne_outputFrameCountMatchesFactor() {
        val decimator = Decimator(inputSampleRateHz = 96000, outputSampleRateHz = 48000, channelCount = 2)
        val input = FloatArray(2048 * 2)
        val output = FloatArray(input.size)
        val outFrames = decimator.process(input, 2048, output)
        assertThat(outFrames).isEqualTo(1024)
    }

    @Test
    fun lowFrequencyBelowNewNyquist_passesWithoutAttenuation() {
        // 1kHz is far below the 24kHz Nyquist of a 96k->48k (2:1) decimation — after the
        // filter settles, RMS in vs RMS out should match closely (allow settle-in margin).
        val decimator = Decimator(inputSampleRateHz = 96000, outputSampleRateHz = 48000, channelCount = 1)
        val frameCount = 20000
        val input = sineWave(96000, 1000.0, frameCount, 1)
        val output = FloatArray(frameCount)
        val outFrames = decimator.process(input, frameCount, output)

        val inputRms = rms(input, 5000, frameCount - 5000, 1, 0)
        val outputRms = rms(output, outFrames / 4, outFrames - outFrames / 4, 1, 0)
        // A low-passed low frequency should retain the vast majority of its amplitude.
        assertThat(outputRms).isGreaterThan(inputRms * 0.9)
    }

    @Test
    fun frequencyAboveNewNyquist_isSuppressedNotAliased() {
        // 30kHz would alias down to 96k-30k folded through 48k's Nyquist (24k) into a
        // clearly-audible ~18kHz tone if simply decimated without filtering — a correct
        // anti-aliasing filter must suppress it well below the passband reference level
        // instead of letting it fold back in near-full-strength.
        val decimator = Decimator(inputSampleRateHz = 96000, outputSampleRateHz = 48000, channelCount = 1)
        val frameCount = 20000
        val passbandInput = sineWave(96000, 1000.0, frameCount, 1)
        val aliasCandidateInput = sineWave(96000, 30000.0, frameCount, 1)
        val passbandOutput = FloatArray(frameCount)
        val aliasOutput = FloatArray(frameCount)
        val passbandOutFrames = decimator.process(passbandInput, frameCount, passbandOutput)

        val decimator2 = Decimator(inputSampleRateHz = 96000, outputSampleRateHz = 48000, channelCount = 1)
        val aliasOutFrames = decimator2.process(aliasCandidateInput, frameCount, aliasOutput)

        val passbandRms = rms(passbandOutput, passbandOutFrames / 4, passbandOutFrames - passbandOutFrames / 4, 1, 0)
        val aliasRms = rms(aliasOutput, aliasOutFrames / 4, aliasOutFrames - aliasOutFrames / 4, 1, 0)
        // The above-Nyquist tone's surviving energy must be a small fraction of the
        // passband reference — proves it was attenuated, not merely decimated-through.
        assertThat(aliasRms).isLessThan(passbandRms * 0.1)
    }

    @Test
    fun statePersistsAcrossCallBoundaries_matchesSingleCallResult() {
        // Two small process() calls back-to-back must produce the same result as one big
        // call — verifies filter history AND the keep/drop phase counter both survive
        // across calls (a real drain loop calls process() once per ~512-frame block, not
        // once for a whole recording).
        val frameCount = 4000
        val input = sineWave(96000, 1000.0, frameCount, 1)

        val wholeDecimator = Decimator(96000, 48000, 1)
        val wholeOutput = FloatArray(frameCount)
        val wholeOutFrames = wholeDecimator.process(input, frameCount, wholeOutput)

        val splitDecimator = Decimator(96000, 48000, 1)
        val splitOutput = FloatArray(frameCount)
        val firstHalf = input.copyOfRange(0, 2000)
        val secondHalf = input.copyOfRange(2000, 4000)
        val firstOutBuf = FloatArray(2000)
        val secondOutBuf = FloatArray(2000)
        val firstOutFrames = splitDecimator.process(firstHalf, 2000, firstOutBuf)
        val secondOutFrames = splitDecimator.process(secondHalf, 2000, secondOutBuf)
        System.arraycopy(firstOutBuf, 0, splitOutput, 0, firstOutFrames)
        System.arraycopy(secondOutBuf, 0, splitOutput, firstOutFrames, secondOutFrames)

        assertThat(firstOutFrames + secondOutFrames).isEqualTo(wholeOutFrames)
        for (i in 0 until wholeOutFrames) {
            assertThat(splitOutput[i]).isWithin(1e-6f).of(wholeOutput[i])
        }
    }
}
