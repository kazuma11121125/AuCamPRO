package com.procamera.recorder.encoder

import kotlin.random.Random

/**
 * Converts Float32 PCM (Oboe's native format) to 16-bit integer PCM (what MediaCodec's
 * AAC-LC encoder is universally supported to accept — see docs/ARCHITECTURE.md's
 * judgment log on why Float32 input isn't assumed portable across encoder
 * implementations). Uses TPDF (Triangular Probability Density Function) dither rather
 * than naive truncation/rounding: truncation alone correlates the resulting quantization
 * error with the signal itself, which is audible as low-level distortion on quiet
 * passages; TPDF dither decorrelates it into noise instead, which is the standard
 * professional-audio technique for this exact conversion (§4.2).
 */
object PcmDither {

    /**
     * Converts the first [sampleCount] elements of [input] (range approximately [-1,1],
     * values outside are clamped) into [output] in place — both must be sized >=
     * [sampleCount], letting callers reuse fixed-size scratch buffers across blocks of
     * varying length instead of reallocating (or, worse, allocating a throwaway output
     * array whose result then never gets read — the real-device bug this signature
     * replaces; see AudioEncoder's git history). [random] is injectable for deterministic
     * tests; production callers should use a single long-lived instance across calls (not
     * reseed per-block) so the dither sequence doesn't repeat in an audible pattern.
     */
    fun floatToInt16Tpdf(input: FloatArray, output: ShortArray, sampleCount: Int, random: Random = Random.Default) {
        require(input.size >= sampleCount) { "input too small: ${input.size} < $sampleCount" }
        require(output.size >= sampleCount) { "output too small: ${output.size} < $sampleCount" }
        for (i in 0 until sampleCount) {
            val clamped = input[i].coerceIn(-1f, 1f)
            val scaled = clamped * SCALE

            // TPDF: sum of two independent uniform[-0.5, 0.5) randoms. This is the
            // standard construction — a single uniform random gives RPDF (rectangular)
            // dither, which is not sufficient to fully decorrelate quantization error
            // from the signal; the triangular distribution from summing two uniforms is
            // what the "TPDF" name refers to and what §4.2 specifically asks for.
            val dither = (random.nextFloat() - 0.5f) + (random.nextFloat() - 0.5f)

            val dithered = (scaled + dither).let { Math.round(it) }
            output[i] = dithered.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private const val SCALE = 32767.0f // Short.MAX_VALUE, kept symmetric (not using MIN_VALUE's extra magnitude)
}
