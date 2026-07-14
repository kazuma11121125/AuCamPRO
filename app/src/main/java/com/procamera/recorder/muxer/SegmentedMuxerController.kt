package com.procamera.recorder.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Owns the MediaMuxer lifecycle for §4.4: buffers samples in a pending queue until both
 * tracks' formats are known (`INFO_OUTPUT_FORMAT_CHANGED` from both encoders), then
 * writes to a segment file, seamlessly rotating to a new file at video keyframe
 * boundaries per [SegmentRotationPlanner]'s timing decisions — the crash-safety
 * requirement being that an unclosed `MediaMuxer` loses its entire file (no moov box),
 * so bounding each segment's duration bounds the damage.
 *
 * `MediaMuxer.writeSampleData` is not documented as thread-safe for concurrent callers,
 * and video samples arrive from the Video Encoder Callback thread while audio samples
 * arrive from the Audio Encoder drain thread (see docs/ARCHITECTURE.md's thread model) —
 * every method here that touches muxer state is `@Synchronized`.
 *
 * **確信度の明示 / known limitation**: rotating both tracks at *exactly* the same
 * instant with zero loss/duplication requires routing in-flight samples from either
 * thread against the exact rotation boundary PTS, which this implements via
 * [rotationBoundaryPtsUs] comparison — but the two encoder threads are not
 * hard-synchronized with each other, so a genuinely adversarial interleaving (e.g. the
 * audio thread stalls for an unusually long time right at a rotation boundary) is not
 * fully ruled out by this implementation alone. This is exactly the kind of
 * cross-thread-timing correctness that can't be verified by compiling or by a host unit
 * test — Phase 5's real-device verification must specifically stress-test segment
 * rotation (e.g. record continuously across several rotation boundaries and verify each
 * resulting segment file has no A/V gap or duplicate frame at its start/end).
 */
class SegmentedMuxerController(
    private val outputPathForSegment: (segmentIndex: Int) -> String,
    segmentDurationUs: Long = DEFAULT_SEGMENT_DURATION_US,
    /**
     * MP4 rotation matrix hint (0/90/180/270), applied to every segment's [MediaMuxer] via
     * [MediaMuxer.setOrientationHint] before `start()`. This app's window is locked to
     * `sensorLandscape` (real-device feedback: the UI itself should stay landscape-only),
     * but the camera's encoded frames are always in the sensor's fixed native orientation
     * regardless of how the user physically holds the phone — so a portrait-held
     * recording needs this container-level tag for players to display it upright, since
     * nothing about the actual pixel data changes. Sampled once at recording start (see
     * `RecordingPipeline.startRecording`'s call site) rather than re-evaluated per
     * segment — matching ordinary camera app behavior, where rotating the phone mid-take
     * doesn't retroactively change already-committed segments' orientation tag.
     */
    private val orientationHintDegrees: Int = 0,
) {
    private data class PendingSample(
        val isVideo: Boolean,
        val buffer: ByteBuffer,
        val bufferInfo: MediaCodec.BufferInfo,
    )

    private val rotationPlanner = SegmentRotationPlanner(segmentDurationUs)
    private val lock = Any()

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var started = false
    private val pendingSamples = mutableListOf<PendingSample>()

    private var currentMuxer: MediaMuxer? = null
    private var currentVideoTrack = -1
    private var currentAudioTrack = -1

    // Set when a rotation decision fires; cleared once the old muxer is confirmed drained
    // (see rotate()/routeToNew()).
    private var rotationBoundaryPtsUs: Long? = null
    private var oldMuxer: MediaMuxer? = null
    private var oldVideoTrack = -1
    private var oldAudioTrack = -1
    private var samplesRoutedToNewSinceRotation = 0

    fun onVideoFormatChanged(format: MediaFormat) = synchronized(lock) {
        videoFormat = format
        tryStart()
    }

    fun onAudioFormatChanged(format: MediaFormat) = synchronized(lock) {
        audioFormat = format
        tryStart()
    }

    private fun tryStart() {
        if (started) return
        val vf = videoFormat ?: return
        val af = audioFormat ?: return

        val muxer = MediaMuxer(outputPathForSegment(0), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(orientationHintDegrees)
        currentVideoTrack = muxer.addTrack(vf)
        currentAudioTrack = muxer.addTrack(af)
        muxer.start()
        currentMuxer = muxer
        started = true

        // §4.4: samples that arrived before both formats were known are held, not
        // dropped, and written out now in their original order.
        for (pending in pendingSamples) {
            writeToCurrent(pending.isVideo, pending.buffer, pending.bufferInfo)
        }
        pendingSamples.clear()
    }

    fun onVideoSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) = synchronized(lock) {
        if (!started) {
            pendingSamples += toPendingSample(true, buffer, bufferInfo)
            return@synchronized
        }

        // A rotation may be mid-flight: route via MuxerSampleRouter, which encodes the
        // straggler rule — a sample from before the boundary (the other track's thread
        // hadn't caught up yet when the video thread triggered rotation) MUST go to the
        // old muxer, never the new one, or that segment's audio/video would gain samples
        // that belong to the previous file.
        val boundary = rotationBoundaryPtsUs
        if (boundary != null) {
            routeDuringRotation(true, buffer, bufferInfo, boundary)
            return@synchronized
        }

        val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val decision = rotationPlanner.onVideoFrame(bufferInfo.presentationTimeUs, isKeyframe)
        if (decision is SegmentRotationPlanner.Decision.Rotate) {
            rotate(bufferInfo.presentationTimeUs, decision.newSegmentIndex)
            routeDuringRotation(true, buffer, bufferInfo, bufferInfo.presentationTimeUs)
        } else {
            writeToCurrent(true, buffer, bufferInfo)
        }
    }

    fun onAudioSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) = synchronized(lock) {
        if (!started) {
            pendingSamples += toPendingSample(false, buffer, bufferInfo)
            return@synchronized
        }

        val boundary = rotationBoundaryPtsUs
        if (boundary != null) {
            routeDuringRotation(false, buffer, bufferInfo, boundary)
        } else {
            writeToCurrent(false, buffer, bufferInfo)
        }
    }

    /** Only valid while [rotationBoundaryPtsUs] is non-null (a rotation is mid-flight). */
    private fun routeDuringRotation(isVideo: Boolean, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo, boundaryPtsUs: Long) {
        val route = MuxerSampleRouter.decide(
            started = true,
            boundaryPtsUs = boundaryPtsUs,
            samplePtsUs = bufferInfo.presentationTimeUs,
            oldMuxerAvailable = oldMuxer != null,
        )
        if (route == MuxerSampleRouter.Route.OLD) {
            writeToOld(isVideo, buffer, bufferInfo)
        } else {
            routeToNew(isVideo, buffer, bufferInfo)
        }
    }

    /** §4.4's stop sequence: caller has already sent EOS to both encoders and drained them. */
    fun stop() = synchronized(lock) {
        currentMuxer?.let { muxer ->
            muxer.stop()
            muxer.release()
        }
        oldMuxer?.let { muxer ->
            muxer.stop()
            muxer.release()
        }
        currentMuxer = null
        oldMuxer = null
        started = false
    }

    private fun rotate(boundaryPtsUs: Long, newSegmentIndex: Int) {
        val next = MediaMuxer(outputPathForSegment(newSegmentIndex), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        next.setOrientationHint(orientationHintDegrees)
        val nextVideoTrack = next.addTrack(requireNotNull(videoFormat))
        val nextAudioTrack = next.addTrack(requireNotNull(audioFormat))
        next.start()

        oldMuxer = currentMuxer
        oldVideoTrack = currentVideoTrack
        oldAudioTrack = currentAudioTrack

        currentMuxer = next
        currentVideoTrack = nextVideoTrack
        currentAudioTrack = nextAudioTrack

        rotationBoundaryPtsUs = boundaryPtsUs
        samplesRoutedToNewSinceRotation = 0
    }

    private fun routeToNew(isVideo: Boolean, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        writeToCurrent(isVideo, buffer, bufferInfo)
        samplesRoutedToNewSinceRotation += 1
        // Heuristic drain-complete signal: once several samples have landed cleanly in
        // the new segment, any straggling old-segment samples (from the other track's
        // thread, still mid-flight at the rotation instant) have almost certainly already
        // been written too — see the class doc's confidence caveat on this not being a
        // hard guarantee.
        if (samplesRoutedToNewSinceRotation >= OLD_MUXER_DRAIN_GRACE_SAMPLES) {
            oldMuxer?.let { muxer ->
                muxer.stop()
                muxer.release()
            }
            oldMuxer = null
            rotationBoundaryPtsUs = null
        }
    }

    private fun writeToCurrent(isVideo: Boolean, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val muxer = currentMuxer ?: return
        val trackIndex = if (isVideo) currentVideoTrack else currentAudioTrack
        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
    }

    private fun writeToOld(isVideo: Boolean, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val muxer = oldMuxer ?: return
        val trackIndex = if (isVideo) oldVideoTrack else oldAudioTrack
        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
    }

    private fun toPendingSample(isVideo: Boolean, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): PendingSample {
        // Both the encoder's output ByteBuffer AND its BufferInfo are only valid /
        // non-aliased until the caller's next callback iteration (encoder wrappers
        // commonly reuse a single BufferInfo instance across calls — see AudioEncoder's
        // drain loop) — samples held in the pending queue (before both track formats are
        // known) must deep-copy both, not reference either.
        val bufferCopy = ByteBuffer.allocateDirect(bufferInfo.size)
        val original = buffer.duplicate()
        original.position(bufferInfo.offset)
        original.limit(bufferInfo.offset + bufferInfo.size)
        bufferCopy.put(original)
        bufferCopy.flip()

        val infoCopy = MediaCodec.BufferInfo().apply {
            set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
        }
        return PendingSample(isVideo, bufferCopy, infoCopy)
    }

    companion object {
        const val DEFAULT_SEGMENT_DURATION_US = 5 * 60 * 1_000_000L // §4.4 default: 5 minutes
        const val OLD_MUXER_DRAIN_GRACE_SAMPLES = 8
    }
}
