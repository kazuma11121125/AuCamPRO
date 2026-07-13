package com.procamera.recorder.muxer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private class FakeClock(
    private var nanoTime: Long = 1_000_000_000L,
    private var elapsedRealtime: Long = 1_000_000_000L,
) : PtsClockDomain.Clock {
    override fun nanoTimeNanos(): Long = nanoTime
    override fun elapsedRealtimeNanos(): Long = elapsedRealtime

    fun advanceBoth(nanos: Long) {
        nanoTime += nanos
        elapsedRealtime += nanos
    }

    fun setBootTimeGap(gapNanos: Long) {
        // Simulates the constant offset that accumulates from any pre-recording deep
        // sleep: elapsedRealtimeNanos leads nanoTime by gapNanos from here on.
        elapsedRealtime = nanoTime + gapNanos
    }
}

class PtsClockDomainTest {

    // ---------- Realtime source ----------

    @Test
    fun realtime_zeroBootTimeGap_sensorTimestampAtStartMapsToApproximatelyZero() {
        val clock = FakeClock(nanoTime = 5_000_000_000L, elapsedRealtime = 5_000_000_000L)
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Realtime, nowNanos = clock.nanoTimeNanos())

        // A frame captured at exactly the calibration instant.
        val ptsUs = domain.normalizeVideoPtsUs(sensorTimestampNanos = 5_000_000_000L)
        assertThat(ptsUs).isEqualTo(0L)
    }

    @Test
    fun realtime_withBootTimeGap_stillCorrectlyCancelsTheGap() {
        val clock = FakeClock(nanoTime = 5_000_000_000L)
        clock.setBootTimeGap(gapNanos = 300_000_000L) // e.g. 300ms accumulated from a prior sleep
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Realtime, nowNanos = clock.nanoTimeNanos())

        // A frame captured 40ms (in real/elapsedRealtime terms) after calibration.
        val sensorTimestamp = clock.elapsedRealtimeNanos() + 40_000_000L
        val ptsUs = domain.normalizeVideoPtsUs(sensorTimestamp)

        // The 300ms boot-time gap must be fully cancelled by the offset; only the 40ms
        // of actual elapsed recording time should remain.
        assertThat(ptsUs).isEqualTo(40_000L)
    }

    @Test
    fun realtime_secondFrameLaterProducesLargerPts() {
        val clock = FakeClock(nanoTime = 0L, elapsedRealtime = 0L)
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Realtime, nowNanos = 0L)

        val first = domain.normalizeVideoPtsUs(sensorTimestampNanos = 0L)
        val second = domain.normalizeVideoPtsUs(sensorTimestampNanos = 33_333_333L) // ~1 frame @30fps
        assertThat(first).isEqualTo(0L)
        assertThat(second).isEqualTo(33_333L)
    }

    // ---------- Unknown source ----------

    @Test
    fun unknown_returnsNullUntilCalibrationSampleCountReached() {
        val clock = FakeClock()
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Unknown, nowNanos = clock.nanoTimeNanos())

        assertThat(domain.isVideoCalibrated).isFalse()
        repeat(PtsClockDomain.UNKNOWN_CALIBRATION_SAMPLE_COUNT - 1) { i ->
            domain.addUnknownCalibrationSample(sensorTimestampNanos = i * 1000L, arrivalNanoTime = i * 1000L + 500L)
            assertThat(domain.isVideoCalibrated).isFalse()
            assertThat(domain.normalizeVideoPtsUs(i * 1000L)).isNull()
        }
    }

    @Test
    fun unknown_calibratesAfterEnoughSamplesAndAppliesMedianOffset() {
        val clock = FakeClock(nanoTime = 0L, elapsedRealtime = 0L)
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Unknown, nowNanos = clock.nanoTimeNanos())

        // Every sample has arrival - sensorTimestamp == 700ns (a clean, noise-free offset).
        repeat(PtsClockDomain.UNKNOWN_CALIBRATION_SAMPLE_COUNT) { i ->
            val sensorTs = i * 1_000_000L
            domain.addUnknownCalibrationSample(sensorTimestampNanos = sensorTs, arrivalNanoTime = sensorTs + 700L)
        }
        assertThat(domain.isVideoCalibrated).isTrue()

        // recordingStartNanos was 0 (the clock's initial nanoTime), so a frame with
        // sensorTimestamp = 10_000_000 should map to (10_000_000 + 700) / 1000 us.
        val ptsUs = domain.normalizeVideoPtsUs(sensorTimestampNanos = 10_000_000L)
        assertThat(ptsUs).isEqualTo((10_000_000L + 700L) / 1000L)
    }

    @Test
    fun unknown_medianIsRobustToASingleOutlierSample() {
        val clock = FakeClock(nanoTime = 0L, elapsedRealtime = 0L)
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Unknown, nowNanos = clock.nanoTimeNanos())

        // 9 clean samples at offset=1000ns, 1 wild outlier at offset=50_000_000ns
        // (simulating one badly scheduled callback). The median must ignore it; a mean
        // would not.
        repeat(PtsClockDomain.UNKNOWN_CALIBRATION_SAMPLE_COUNT - 1) { i ->
            val sensorTs = i * 1_000_000L
            domain.addUnknownCalibrationSample(sensorTimestampNanos = sensorTs, arrivalNanoTime = sensorTs + 1000L)
        }
        domain.addUnknownCalibrationSample(sensorTimestampNanos = 99_000_000L, arrivalNanoTime = 99_000_000L + 50_000_000L)

        assertThat(domain.isVideoCalibrated).isTrue()
        val ptsUs = domain.normalizeVideoPtsUs(sensorTimestampNanos = 20_000_000L)
        // With the outlier excluded by the median, the effective offset should still be
        // 1000ns, not something dragged toward the 50ms outlier.
        assertThat(ptsUs).isEqualTo((20_000_000L + 1000L) / 1000L)
    }

    // ---------- Monotonic guard ----------

    @Test
    fun videoPts_nonIncreasingFrameIsDropped() {
        val clock = FakeClock(nanoTime = 0L, elapsedRealtime = 0L)
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Realtime, nowNanos = 0L)

        val first = domain.normalizeVideoPtsUs(sensorTimestampNanos = 10_000_000L)
        val repeated = domain.normalizeVideoPtsUs(sensorTimestampNanos = 10_000_000L) // same instant again
        val earlier = domain.normalizeVideoPtsUs(sensorTimestampNanos = 5_000_000L) // clock went backwards

        assertThat(first).isNotNull()
        assertThat(repeated).isNull()
        assertThat(earlier).isNull()
    }

    @Test
    fun audioPts_nonIncreasingIsDropped() {
        val clock = FakeClock()
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Realtime, nowNanos = clock.nanoTimeNanos())
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
        val clock = FakeClock(nanoTime = 0L, elapsedRealtime = 0L)
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Realtime, nowNanos = 0L)

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
        val clock = FakeClock(nanoTime = 10_000_000_000L, elapsedRealtime = 10_000_000_000L)
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Realtime, nowNanos = clock.nanoTimeNanos())

        // A frame whose sensor timestamp predates the recording-start instant (e.g. was
        // already in flight when start() was called).
        val ptsUs = domain.normalizeVideoPtsUs(sensorTimestampNanos = 9_000_000_000L)
        assertThat(ptsUs).isEqualTo(0L)
    }

    @Test
    fun audioPts_neverGoesNegativeEvenWithNegativeAnchorOffset() {
        val clock = FakeClock(nanoTime = 10_000_000_000L, elapsedRealtime = 10_000_000_000L)
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Realtime, nowNanos = clock.nanoTimeNanos())
        // Anchor set to before recording-start (simulating an anchor race).
        domain.startAudioAnchor(nowNanos = 9_000_000_000L)

        val ptsUs = domain.normalizeAudioPtsUs(cumulativeSampleCount = 0, sampleRateHz = 48000)
        assertThat(ptsUs).isEqualTo(0L)
    }

    // ---------- Audio PTS basic correctness ----------

    @Test
    fun audioPts_derivedPurelyFromSampleCountNotWallClock() {
        val clock = FakeClock(nanoTime = 0L, elapsedRealtime = 0L)
        val domain = PtsClockDomain(clock)
        domain.start(PtsClockDomain.TimestampSource.Realtime, nowNanos = 0L)
        domain.startAudioAnchor(nowNanos = 0L)

        // 48000 samples at 48kHz == exactly 1 second == 1_000_000us, regardless of what
        // the wall clock (nanoTime) does in between — it is never consulted again after
        // the anchor.
        clock.advanceBoth(999_000_000_000L) // wall clock jumps wildly; must not affect this
        val ptsUs = domain.normalizeAudioPtsUs(cumulativeSampleCount = 48_000, sampleRateHz = 48000)
        assertThat(ptsUs).isEqualTo(1_000_000L)
    }
}
