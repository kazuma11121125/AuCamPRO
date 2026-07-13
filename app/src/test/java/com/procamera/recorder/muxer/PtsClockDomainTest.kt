package com.procamera.recorder.muxer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private class FakeClock(private var nanoTime: Long = 1_000_000_000L) : PtsClockDomain.Clock {
    override fun nanoTimeNanos(): Long = nanoTime

    fun advance(nanos: Long) {
        nanoTime += nanos
    }
}

class PtsClockDomainTest {

    // ---------- Video PTS: direct CLOCK_MONOTONIC pass-through ----------
    //
    // presentationTimeUs from the video encoder is already CLOCK_MONOTONIC (verified on
    // real hardware — see PtsClockDomain's class doc); no source branching or calibration
    // is needed, just epoch-zeroing against recordingStartNanos.

    @Test
    fun video_frameAtStartInstantMapsToZero() {
        val clock = FakeClock(nanoTime = 5_000_000_000L)
        val domain = PtsClockDomain(clock)
        domain.start(nowNanos = clock.nanoTimeNanos())

        val ptsUs = domain.normalizeVideoPtsUs(presentationTimeNanos = 5_000_000_000L)
        assertThat(ptsUs).isEqualTo(0L)
    }

    @Test
    fun video_secondFrameLaterProducesLargerPts() {
        val clock = FakeClock(nanoTime = 0L)
        val domain = PtsClockDomain(clock)
        domain.start(nowNanos = 0L)

        val first = domain.normalizeVideoPtsUs(presentationTimeNanos = 0L)
        val second = domain.normalizeVideoPtsUs(presentationTimeNanos = 33_333_333L) // ~1 frame @30fps
        assertThat(first).isEqualTo(0L)
        assertThat(second).isEqualTo(33_333L)
    }

    // ---------- Monotonic guard ----------

    @Test
    fun videoPts_nonIncreasingFrameIsDropped() {
        val clock = FakeClock(nanoTime = 0L)
        val domain = PtsClockDomain(clock)
        domain.start(nowNanos = 0L)

        val first = domain.normalizeVideoPtsUs(presentationTimeNanos = 10_000_000L)
        val repeated = domain.normalizeVideoPtsUs(presentationTimeNanos = 10_000_000L) // same instant again
        val earlier = domain.normalizeVideoPtsUs(presentationTimeNanos = 5_000_000L) // clock went backwards

        assertThat(first).isNotNull()
        assertThat(repeated).isNull()
        assertThat(earlier).isNull()
    }

    @Test
    fun audioPts_nonIncreasingIsDropped() {
        val clock = FakeClock()
        val domain = PtsClockDomain(clock)
        domain.start(nowNanos = clock.nanoTimeNanos())
        domain.startAudioAnchor(nowNanos = clock.nanoTimeNanos())

        val first = domain.normalizeAudioPtsUs(cumulativeSampleCount = 4800, sampleRateHz = 48000) // 100ms in
        val same = domain.normalizeAudioPtsUs(cumulativeSampleCount = 4800, sampleRateHz = 48000)
        val backwards = domain.normalizeAudioPtsUs(cumulativeSampleCount = 2400, sampleRateHz = 48000)

        assertThat(first).isNotNull()
        assertThat(same).isNull()
        assertThat(backwards).isNull()
    }

    // ---------- Frame-correlation audio anchor ----------

    @Test
    fun frameCorrelationAnchor_backCalculatesTrueCaptureTimeOfSampleZero() {
        val clock = FakeClock(nanoTime = 0L)
        val domain = PtsClockDomain(clock)
        domain.start(nowNanos = 0L)

        // 48kHz stream; queried at frame position 4800 (100ms in) when the correlated
        // monotonic time was 150ms. That means sample 0 was truly captured at 50ms, not
        // at whatever wall-clock instant the first callback happened to be processed.
        domain.startAudioAnchorFromFrameCorrelation(
            framePosition = 4800,
            timeNanos = 150_000_000L,
            sampleRateHz = 48000,
        )

        // Sample 0 itself should map to PTS 50ms.
        val ptsAtSampleZero = domain.normalizeAudioPtsUs(cumulativeSampleCount = 0, sampleRateHz = 48000)
        assertThat(ptsAtSampleZero).isEqualTo(50_000L)
    }

    // ---------- Negative PTS clamp ----------

    @Test
    fun videoPts_frameCapturedBeforeStartClampsToZeroInsteadOfGoingNegative() {
        val clock = FakeClock(nanoTime = 10_000_000_000L)
        val domain = PtsClockDomain(clock)
        domain.start(nowNanos = clock.nanoTimeNanos())

        // A frame whose presentation time predates the recording-start instant (e.g. was
        // already in flight when start() was called).
        val ptsUs = domain.normalizeVideoPtsUs(presentationTimeNanos = 9_000_000_000L)
        assertThat(ptsUs).isEqualTo(0L)
    }

    @Test
    fun audioPts_neverGoesNegativeEvenWithNegativeAnchorOffset() {
        val clock = FakeClock(nanoTime = 10_000_000_000L)
        val domain = PtsClockDomain(clock)
        domain.start(nowNanos = clock.nanoTimeNanos())
        // Anchor set to before recording-start (simulating an anchor race).
        domain.startAudioAnchor(nowNanos = 9_000_000_000L)

        val ptsUs = domain.normalizeAudioPtsUs(cumulativeSampleCount = 0, sampleRateHz = 48000)
        assertThat(ptsUs).isEqualTo(0L)
    }

    // ---------- Audio PTS basic correctness ----------

    @Test
    fun audioPts_derivedPurelyFromSampleCountNotWallClock() {
        val clock = FakeClock(nanoTime = 0L)
        val domain = PtsClockDomain(clock)
        domain.start(nowNanos = 0L)
        domain.startAudioAnchor(nowNanos = 0L)

        // 48000 samples at 48kHz == exactly 1 second == 1_000_000us, regardless of what
        // the wall clock (nanoTime) does in between — it is never consulted again after
        // the anchor.
        clock.advance(999_000_000_000L) // wall clock jumps wildly; must not affect this
        val ptsUs = domain.normalizeAudioPtsUs(cumulativeSampleCount = 48_000, sampleRateHz = 48000)
        assertThat(ptsUs).isEqualTo(1_000_000L)
    }
}
