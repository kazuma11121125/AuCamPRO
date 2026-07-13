package com.procamera.recorder.muxer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MuxerSampleRouterTest {

    @Test
    fun notStarted_isAlwaysPending_regardlessOfBoundary() {
        assertThat(MuxerSampleRouter.decide(started = false, boundaryPtsUs = null, samplePtsUs = 0L, oldMuxerAvailable = false))
            .isEqualTo(MuxerSampleRouter.Route.PENDING)
        assertThat(MuxerSampleRouter.decide(started = false, boundaryPtsUs = 500L, samplePtsUs = 100L, oldMuxerAvailable = true))
            .isEqualTo(MuxerSampleRouter.Route.PENDING)
    }

    @Test
    fun started_noRotationInFlight_alwaysRoutesNew() {
        assertThat(MuxerSampleRouter.decide(started = true, boundaryPtsUs = null, samplePtsUs = 12_345L, oldMuxerAvailable = false))
            .isEqualTo(MuxerSampleRouter.Route.NEW)
    }

    @Test
    fun rotationInFlight_stragglerBeforeBoundary_routesToOldMuxer() {
        // The bug this locks in: a sample whose thread hadn't caught up to the rotation
        // boundary yet must land in the OLD segment, not the new one.
        assertThat(
            MuxerSampleRouter.decide(started = true, boundaryPtsUs = 1_000_000L, samplePtsUs = 999_000L, oldMuxerAvailable = true)
        ).isEqualTo(MuxerSampleRouter.Route.OLD)
    }

    @Test
    fun rotationInFlight_sampleAtOrAfterBoundary_routesToNewMuxer() {
        assertThat(
            MuxerSampleRouter.decide(started = true, boundaryPtsUs = 1_000_000L, samplePtsUs = 1_000_000L, oldMuxerAvailable = true)
        ).isEqualTo(MuxerSampleRouter.Route.NEW)
        assertThat(
            MuxerSampleRouter.decide(started = true, boundaryPtsUs = 1_000_000L, samplePtsUs = 1_500_000L, oldMuxerAvailable = true)
        ).isEqualTo(MuxerSampleRouter.Route.NEW)
    }

    @Test
    fun rotationInFlight_stragglerAfterOldMuxerAlreadyClosed_fallsBackToNewMuxer() {
        // The drain-grace heuristic (SegmentedMuxerController.OLD_MUXER_DRAIN_GRACE_SAMPLES)
        // may have already closed the old muxer by the time a very late straggler arrives —
        // there is no valid destination for it but the new segment, even though its PTS is
        // technically before the boundary.
        assertThat(
            MuxerSampleRouter.decide(started = true, boundaryPtsUs = 1_000_000L, samplePtsUs = 999_000L, oldMuxerAvailable = false)
        ).isEqualTo(MuxerSampleRouter.Route.NEW)
    }
}
