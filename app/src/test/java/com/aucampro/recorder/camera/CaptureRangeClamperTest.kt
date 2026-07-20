package com.aucampro.recorder.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureRangeClamperTest {

    private val clamper = CaptureRangeClamper(
        sensitivityRange = 100..3200,
        exposureTimeRangeNanos = 1_000_000L..500_000_000L,
        minFocusDistanceDiopters = 10f,
    )

    @Test
    fun clampSensitivity_withinRange_unchanged() {
        assertThat(clamper.clampSensitivity(800)).isEqualTo(800)
    }

    @Test
    fun clampSensitivity_belowRange_clampedToMin() {
        assertThat(clamper.clampSensitivity(50)).isEqualTo(100)
    }

    @Test
    fun clampSensitivity_aboveRange_clampedToMax() {
        assertThat(clamper.clampSensitivity(6400)).isEqualTo(3200)
    }

    @Test
    fun clampExposureTimeNanos_clampsToSensorRange() {
        assertThat(clamper.clampExposureTimeNanos(100)).isEqualTo(1_000_000L)
        assertThat(clamper.clampExposureTimeNanos(1_000_000_000L)).isEqualTo(500_000_000L)
        assertThat(clamper.clampExposureTimeNanos(20_000_000L)).isEqualTo(20_000_000L)
    }

    @Test
    fun clampFocusDistance_clampsToZeroAndMinDistance() {
        assertThat(clamper.clampFocusDistance(-5f)).isEqualTo(0f)
        assertThat(clamper.clampFocusDistance(999f)).isEqualTo(10f)
        assertThat(clamper.clampFocusDistance(5f)).isEqualTo(5f)
    }

    @Test
    fun shutterPreset_exposureTimeNanos_matchesExpectedFraction() {
        assertThat(CaptureRangeClamper.ShutterPreset.S_1_50.exposureTimeNanos()).isEqualTo(20_000_000L)
        assertThat(CaptureRangeClamper.ShutterPreset.S_1_60.exposureTimeNanos()).isEqualTo(16_666_666L)
        assertThat(CaptureRangeClamper.ShutterPreset.S_1_100.exposureTimeNanos()).isEqualTo(10_000_000L)
        assertThat(CaptureRangeClamper.ShutterPreset.S_1_120.exposureTimeNanos()).isEqualTo(8_333_333L)
    }

    @Test
    fun clampExposureTimeNanosToFrameRate_tooSlowForNewFps_clampedDown() {
        // Real-device case (2026-07-20): a persisted ~1/33s shutter speed survives a
        // switch to a 60fps video config and must be pulled down to fit, or Camera2's
        // frameDuration>=exposureTime constraint silently caps the actual fps at ~33.
        val staleShutterNanos = 30_000_000L // ~1/33s
        assertThat(CaptureRangeClamper.clampExposureTimeNanosToFrameRate(staleShutterNanos, 60))
            .isEqualTo(16_666_666L)
    }

    @Test
    fun clampExposureTimeNanosToFrameRate_alreadyFasterThanFrameRate_unchanged() {
        val fastShutterNanos = 4_000_000L // 1/250s
        assertThat(CaptureRangeClamper.clampExposureTimeNanosToFrameRate(fastShutterNanos, 30))
            .isEqualTo(fastShutterNanos)
    }

    @Test
    fun frameDurationNanosForFps_computesAndClamps() {
        val frameDurationRange = 4_000_000L..100_000_000L
        assertThat(clamper.frameDurationNanosForFps(30, frameDurationRange)).isEqualTo(33_333_333L)
        assertThat(clamper.frameDurationNanosForFps(1000, frameDurationRange)).isEqualTo(4_000_000L) // clamped up
        assertThat(clamper.frameDurationNanosForFps(1, frameDurationRange)).isEqualTo(100_000_000L) // clamped down
    }
}
