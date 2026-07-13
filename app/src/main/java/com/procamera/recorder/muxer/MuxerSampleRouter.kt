package com.procamera.recorder.muxer

/**
 * Pure extraction of [SegmentedMuxerController]'s sample-routing decision — the logic
 * behind the straggler bug found (and fixed) during Phase 3 implementation: a sample
 * whose PTS precedes the rotation boundary belongs to the *old* segment even though it
 * arrived after rotation was triggered (its track's encoder thread simply hadn't caught
 * up yet), and routing it to the new segment instead would violate §4.4's no
 * loss/duplication requirement. Extracted so this decision has a test independent of a
 * real `MediaMuxer`/`ByteBuffer` plumbing, which JUnit under the plain (non-Robolectric)
 * runner cannot exercise end-to-end.
 */
object MuxerSampleRouter {

    enum class Route {
        /** Neither track's format is known yet — hold in the pending queue (§4.4). */
        PENDING,

        /** Belongs to the segment being rotated away from. */
        OLD,

        /** Belongs to the current (possibly newly-rotated-into) segment. */
        NEW,
    }

    /**
     * @param started whether both track formats are known and the first muxer has started.
     * @param boundaryPtsUs the rotation boundary PTS if a rotation is currently mid-flight
     *   (i.e. the old muxer may still be draining stragglers), else null.
     * @param samplePtsUs this sample's presentation timestamp.
     * @param oldMuxerAvailable whether the old muxer instance is still open to receive a
     *   straggler (it may already have been closed by the drain-grace heuristic).
     */
    fun decide(
        started: Boolean,
        boundaryPtsUs: Long?,
        samplePtsUs: Long,
        oldMuxerAvailable: Boolean,
    ): Route {
        if (!started) return Route.PENDING
        if (boundaryPtsUs != null && samplePtsUs < boundaryPtsUs && oldMuxerAvailable) {
            return Route.OLD
        }
        return Route.NEW
    }
}
