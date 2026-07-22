package com.aucampro.recorder.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.aucampro.recorder.camera.CameraSessionMetrics
import com.aucampro.recorder.muxer.PtsClockDomain
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MediaCodec video encoder in async mode using `createInputSurface()` (§4.4): Camera2
 * renders directly into [inputSurface] (handed to the capture session as one of the two
 * output targets, §4.1's dual-surface requirement); encoded output arrives via
 * [Callback.onEncodedFrame] with an already PTS-normalized BufferInfo.
 *
 * **Design constraint (verified on real hardware) that later Camera2/Service wiring must
 * honor**: with the InputSurface path, frames never pass through `queueInputBuffer` —
 * `bufferInfo.presentationTimeUs` on this OUTPUT callback is already CLOCK_MONOTONIC (the
 * same domain as `System.nanoTime()`), regardless of the camera's
 * `SENSOR_INFO_TIMESTAMP_SOURCE` — see [PtsClockDomain]'s class doc for the real-device
 * finding that corrected this. This class converts that back to nanoseconds (`* 1000L`)
 * and calls `normalizeVideoPtsUs` itself, on the OUTPUT side — never on input, since there
 * is no input side to hook here.
 *
 * Untested framework glue (MediaCodec async callbacks require a live codec instance) —
 * see docs/ARCHITECTURE.md's note on this being compile-verified only until Phase 4 wires
 * a real CameraCaptureSession into it.
 */
class VideoEncoder(
    mimeType: String,
    width: Int,
    height: Int,
    frameRate: Int,
    bitrate: Int,
    private val ptsClockDomain: PtsClockDomain,
    private val callback: Callback,
    /** docs/PERSISTENT_ENCODER_SURFACE_DESIGN_2026-07-21.md Phase 2 — when non-null, this
     * `MediaCodec` binds to a `Surface` created once via `MediaCodec.createPersistentInputSurface()`
     * and owned/reused across recordings by [com.aucampro.recorder.pipeline.RecordingPipeline]
     * (`codec.setInputSurface()`) instead of minting its own per-recording `Surface` via
     * `codec.createInputSurface()`. [inputSurface] is that same instance either way — callers
     * don't need to branch on which path was taken. [stop] must not release a Surface it
     * doesn't own; see [ownsInputSurface]. */
    private val persistentInputSurface: Surface? = null,
    /** docs/CAMERA_SESSION_LATENCY_2026-07-21.md Phase 1 — captured once here, at
     * construction time, rather than read dynamically from
     * `CameraSessionMetrics.activeRecordingAttemptId()` inside [onOutputBufferAvailable]:
     * that callback can fire on `MediaCodec`'s own callback thread after this recording
     * attempt has already ended (e.g. a buffer still draining during stop), by which point
     * the *next* attempt may have already incremented the "current" attempt id. A stale
     * dynamic read would then incorrectly close the next attempt's still-open
     * `AuCam:recordingToFirstVideoFrame` span using a callback that belongs to this one.
     * Capturing the id as a `val` sidesteps that entirely — this instance always reports
     * against the attempt it was actually created for. */
    private val recordingAttemptId: Int = CameraSessionMetrics.activeRecordingAttemptId(),
) {
    interface Callback {
        fun onOutputFormatChanged(format: MediaFormat)
        fun onEncodedFrame(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo)
        fun onError(exception: Exception)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(mimeType)
    val inputSurface: Surface
    private val eosLatch = CountDownLatch(1)

    /** False when [inputSurface] is a caller-owned persistent Surface ([persistentInputSurface])
     * whose lifetime spans multiple recordings — [stop] must not release it, only the
     * per-recording `codec` itself. See [persistentInputSurface]'s doc. */
    private val ownsInputSurface: Boolean = persistentInputSurface == null

    // Reused across every onOutputBufferAvailable call instead of allocating a fresh
    // MediaCodec.BufferInfo per frame (~60/frame/sec at 60fps recording) — real-device
    // finding: MediaCodec's async callbacks are delivered serially on one internal thread,
    // so reuse is safe as long as no callee retains the reference past the synchronous
    // onEncodedFrame() call. MuxerController.onVideoSample's own doc already
    // documents that exact contract (its pending-queue path deep-copies BufferInfo
    // precisely because it's only valid until "the caller's next callback iteration") — so
    // this reuse doesn't change any existing safety assumption, just stops paying for a
    // fresh allocation the consumer side was never relying on. Per-frame allocation across
    // this and other hot UI/encoder paths was measured (via the camera HAL's own
    // "video stream"/"preview stream" FPS telemetry) to correlate with a progressive,
    // GC-pressure-driven frame-rate collapse over a recording session.
    private val reusableBufferInfo = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // §4.4: 1s I-frame interval
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Not used: the InputSurface path feeds the encoder directly from the
                // Camera HAL, bypassing queueInputBuffer entirely.
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                // Checked up front and applied to every early-return path below: the
                // InputSurface path's final post-signalEndOfInputStream() buffer is
                // commonly a zero-size buffer carrying only this flag, so it must not be
                // missed by the size==0 short-circuit just below.
                val isEndOfStream = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                if (info.size == 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codec.releaseOutputBuffer(index, false)
                    if (isEndOfStream) eosLatch.countDown()
                    return
                }
                val buffer = codec.getOutputBuffer(index)
                if (buffer == null) {
                    codec.releaseOutputBuffer(index, false)
                    if (isEndOfStream) eosLatch.countDown()
                    return
                }

                val presentationTimeNanos = info.presentationTimeUs * 1000L
                // 実機で発見・修正 (2026-07-18, PtsClockDomain.isStarted's doc): anchor the
                // recording epoch to this, the first real frame this encoder ever produces,
                // rather than the caller anchoring it up front at "recording requested"
                // time — a full camera-session reconfigure measured ~1.3-1.6s on real
                // hardware between those two moments, and audio starts flowing almost
                // immediately, so anchoring early gave every take that much audio-only lead
                // with no corresponding video.
                if (!ptsClockDomain.isStarted()) {
                    ptsClockDomain.start(presentationTimeNanos)
                    // docs/CAMERA_SESSION_LATENCY_2026-07-21.md Phase 1 — this `isStarted()`
                    // guard already fires exactly once per recording (the real anchor point
                    // this class exists to establish, see this method's own doc above), so
                    // it doubles as the one-shot "first real video frame" measurement point.
                    if (CameraSessionMetrics.endFirstVideoFrame(recordingAttemptId)) {
                        CameraSessionMetrics.logStage(recordingAttemptId, "T8_firstVideoFrame")
                    }
                }
                val normalizedPtsUs = ptsClockDomain.normalizeVideoPtsUs(presentationTimeNanos)
                if (normalizedPtsUs == null) {
                    // Dropped: non-monotonic (§4.3's guard). The Muxer's
                    // pending-queue-before-start behavior (§4.4) is what's expected to
                    // absorb any resulting gap at the very start of recording.
                    codec.releaseOutputBuffer(index, false)
                    if (isEndOfStream) eosLatch.countDown()
                    return
                }

                reusableBufferInfo.set(info.offset, info.size, normalizedPtsUs, info.flags)
                callback.onEncodedFrame(buffer, reusableBufferInfo)
                codec.releaseOutputBuffer(index, false)
                if (isEndOfStream) eosLatch.countDown()
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                callback.onError(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                callback.onOutputFormatChanged(format)
            }
        })

        // release() on failure: configure()/createInputSurface() can throw (e.g. an
        // unsupported format) after createEncoderByType() already allocated the hardware
        // codec instance above — real devices commonly cap concurrent hardware encoders
        // at a small number, so leaking one here on a failed recording start could make
        // the *next* attempt fail too even after the user fixes whatever caused this one
        // to fail.
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (persistentInputSurface != null) {
                // docs/PERSISTENT_ENCODER_SURFACE_DESIGN_2026-07-21.md Phase 2 — binds this
                // fresh MediaCodec instance to a Surface created once (outside this class)
                // via MediaCodec.createPersistentInputSurface(); the Surface's identity
                // (and therefore the CameraCaptureSession's configured output set) stays
                // unchanged across every recording that reuses it.
                codec.setInputSurface(persistentInputSurface)
                inputSurface = persistentInputSurface
            } else {
                inputSurface = codec.createInputSurface()
            }
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun start() {
        codec.start()
    }

    /** Call once, from the recording-stop sequence, before draining/stopping (§4.4). */
    fun signalEndOfStream() {
        codec.signalEndOfInputStream()
    }

    /**
     * Blocks until the EOS output buffer signaled by [signalEndOfStream] has been
     * observed (or [timeoutMs] elapses). Callers must await this before [stop] — calling
     * `codec.stop()` while output is still draining would truncate the segment's final
     * frames, since the async callback runs on MediaCodec's own internal thread.
     */
    fun awaitEndOfStream(timeoutMs: Long = 3_000L): Boolean = eosLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun stop() {
        codec.stop()
        codec.release()
        // Persistent surfaces outlive this one recording's codec — see
        // [persistentInputSurface]'s/[ownsInputSurface]'s docs; the owner
        // (RecordingPipeline) releases it once, when Video CaptureMode itself is exited.
        if (ownsInputSurface) inputSurface.release()
    }
}
