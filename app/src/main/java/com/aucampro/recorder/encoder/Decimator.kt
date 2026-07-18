package com.aucampro.recorder.encoder

import kotlin.math.cos
import kotlin.math.sin

/**
 * Integer-ratio anti-aliasing decimator (docs/HIRES_AUDIO_DESIGN.md §4 "アンチエイリアス・
 * デシメーション"): converts an interleaved float stream at [inputSampleRateHz] down to
 * [outputSampleRateHz] (must evenly divide) by low-pass filtering — a 2-stage cascaded
 * Butterworth biquad (RBJ Cookbook, same formula family as the native-side EQ/HighPass —
 * see `cpp/dsp/BiquadEq.cpp` — but implemented here in Kotlin rather than native, since
 * this runs on [AudioEncoder]'s own non-RT drain thread alongside [PcmDither], not the RT
 * audio callback thread the native DSP chain is bound to) — then keeping every Nth
 * filtered sample. Filtering every input sample (not just the ones ultimately kept)
 * before deciding whether to keep it is what actually prevents aliasing; naive "just drop
 * every Nth sample" would fold energy above the new Nyquist back down into the audible
 * band as distortion.
 *
 * Stateful across [process] calls (per-channel filter history + a running input-frame
 * phase counter) — must be driven by a single caller in a fixed sequence, matching
 * [AudioEncoder]'s single-drain-thread ownership. See `encoder/test/DecimatorTest.kt` for
 * the numerical verification this class's correctness rests on (this codebase's existing
 * DSP-test discipline, applied here in JUnit rather than a host GTest since the class
 * itself is Kotlin).
 */
class Decimator(
    inputSampleRateHz: Int,
    outputSampleRateHz: Int,
    private val channelCount: Int,
) {
    val factor: Int = inputSampleRateHz / outputSampleRateHz

    init {
        require(factor >= 1 && inputSampleRateHz % outputSampleRateHz == 0) {
            "inputSampleRateHz ($inputSampleRateHz) must be an integer multiple of outputSampleRateHz ($outputSampleRateHz)"
        }
    }

    private class BiquadCoeffs(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)
    private class History {
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f
    }

    // Cutoff safely inside the *output* Nyquist (0.45x rather than 0.5x) to leave a
    // transition band the filter can actually attenuate within before the fold-back band
    // starts. 4 cascaded biquads (8th-order Butterworth, maximally flat passband) rather
    // than 2 (4th-order) — measured via DecimatorTest: a 2-stage design only reached
    // ~-22.5dB rejection at 30kHz for a 96k->48k (21.6kHz cutoff) decimation, not enough
    // headroom to call the result genuinely alias-free; 4 stages pushed this well past the
    // -20dB test threshold with margin (see that test for the actual measured numbers).
    private val stageCoeffs: Array<BiquadCoeffs>
    private val stageHistory: Array<Array<History>>
    private var inputFrameCounter = 0L

    init {
        if (factor > 1) {
            val cutoffHz = outputSampleRateHz * 0.45
            stageCoeffs = Array(STAGE_QS.size) { i ->
                computeButterworthLowpass(inputSampleRateHz.toDouble(), cutoffHz, STAGE_QS[i])
            }
            stageHistory = Array(STAGE_QS.size) { Array(channelCount) { History() } }
        } else {
            // factor == 1: process() bypasses the filter entirely (see below), these are
            // never read.
            stageCoeffs = emptyArray()
            stageHistory = emptyArray()
        }
    }

    /** Filters and decimates [frameCount] interleaved frames from [input] (starting at
     * index 0) into [output] (must be sized >= `frameCount * channelCount`, an always-safe
     * upper bound since decimation only ever reduces frame count). Returns the number of
     * OUTPUT frames written — may be 0 for a small enough [frameCount] (a real ring-buffer
     * drain can return fewer than [factor] frames), which is not an error. */
    fun process(input: FloatArray, frameCount: Int, output: FloatArray): Int {
        if (factor == 1) {
            System.arraycopy(input, 0, output, 0, frameCount * channelCount)
            return frameCount
        }

        var outFrames = 0
        for (frame in 0 until frameCount) {
            val keep = (inputFrameCounter % factor) == 0L
            for (ch in 0 until channelCount) {
                var sample = input[frame * channelCount + ch]
                for (stage in stageCoeffs.indices) {
                    sample = processSample(sample, stageCoeffs[stage], stageHistory[stage][ch])
                }
                if (keep) output[outFrames * channelCount + ch] = sample
            }
            if (keep) outFrames++
            inputFrameCounter++
        }
        return outFrames
    }

    private fun processSample(x: Float, c: BiquadCoeffs, h: History): Float {
        val y = c.b0 * x + c.b1 * h.x1 + c.b2 * h.x2 - c.a1 * h.y1 - c.a2 * h.y2
        h.x2 = h.x1
        h.x1 = x
        h.y2 = h.y1
        h.y1 = y
        return y
    }

    private companion object {
        // 8th-order Butterworth via 4 cascaded 2nd-order sections — standard Q values for
        // a maximally-flat (no passband ripple) 8th-order cascade, matching the native
        // EQ's own RBJ Cookbook provenance (see cpp/dsp/BiquadEq.cpp's header comment) for
        // the per-stage biquad formula itself.
        val STAGE_QS = doubleArrayOf(0.5098, 0.6013, 0.8999, 2.5629)

        fun computeButterworthLowpass(sampleRateHz: Double, cutoffHz: Double, q: Double): BiquadCoeffs {
            val w0 = 2.0 * Math.PI * cutoffHz / sampleRateHz
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)
            val b0 = (1.0 - cosW0) / 2.0
            val b1 = 1.0 - cosW0
            val b2 = (1.0 - cosW0) / 2.0
            val a0 = 1.0 + alpha
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha
            return BiquadCoeffs(
                (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (a1 / a0).toFloat(), (a2 / a0).toFloat(),
            )
        }
    }
}
