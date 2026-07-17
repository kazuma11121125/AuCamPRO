package com.aucampro.recorder.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.aucampro.recorder.audio.NativeEngineBridge
import com.aucampro.recorder.muxer.PtsClockDomain
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Dedicated thread (§4.4: "専用エンコーダスレッドがRingBufferからドレインしqueueInputBuffer")
 * draining the native SPSC ring buffer, converting Float32 -> 16-bit PCM via TPDF dither
 * (§4.2, [PcmDither]), and feeding MediaCodec's AAC-LC encoder in synchronous buffer
 * mode. Audio PTS is derived purely from cumulative sample count (§4.3,
 * drift-free) via [ptsClockDomain] — never from this thread's wall-clock timing.
 *
 * Untested framework glue (needs a live MediaCodec + running NativeEngineBridge) — see
 * docs/ARCHITECTURE.md's note on compile-verified-only status pending Phase 4's
 * end-to-end wiring.
 */
class AudioEncoder(
    private val sampleRateHz: Int,
    private val channelCount: Int,
    bitrate: Int,
    private val nativeEngine: NativeEngineBridge,
    private val ptsClockDomain: PtsClockDomain,
    private val callback: Callback,
) {
    interface Callback {
        fun onOutputFormatChanged(format: MediaFormat)
        fun onEncodedFrame(buffer: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo)
        fun onError(exception: Exception)
    }

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private var cumulativeSampleCount = 0L
    private var formatAnnounced = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    /**
     * Starts the encoder and the drain thread. [seedAudioAnchor] (which retries against
     * [NativeEngineBridge.getInputTimestamp] for frame-correlation accuracy — see its doc
     * for why a raw `startAudioAnchor()` call must not be substituted there, since that
     * would silently reintroduce the input-latency offset the frame-correlation path
     * exists to remove) runs on [drainThread], not here: real-device finding — this
     * caller is [com.aucampro.recorder.pipeline.RecordingPipeline.startRecording], which
     * runs the camera session reconfiguration right after this call returns, so blocking
     * here for the anchor's up-to-~2s retry budget delayed that reconfiguration by the
     * same amount, showing up as the first few seconds of video being frozen/blacked out
     * after tapping record. Callers must ensure [nativeEngine] is already started (audio
     * callbacks flowing) before calling this, so a correlation becomes available within
     * the retry budget.
     */
    fun start() {
        codec.start()
        running.set(true)
        drainThread = thread(name = "AudioEncoderDrain", priority = Thread.NORM_PRIORITY) {
            seedAudioAnchor()
            drainLoop()
        }
    }

    /**
     * Real-device finding: [NativeEngineBridge]'s input stream now outlives individual
     * recordings (started once at preview, kept running across record start/stop for
     * continuous metering — see [com.aucampro.recorder.pipeline.RecordingPipeline
     * .ensureAudioEngineStarted]'s doc). That means by the time a *second-or-later*
     * recording's [AudioEncoder] starts, the ring buffer can be holding a stale backlog —
     * up to its ~10s capacity, frozen there since nothing drained it during preview-only
     * (or nothing at all if preview ran long enough to overflow it, silently dropping
     * every write since as an overrun; see SpscRingBuffer's write()). Confirmed on a real
     * device: a recording started ~87s into preview lost effectively all of its audio
     * (0.064s out of a 30s take) — [cumulativeSampleCount] started at 0 while the anchor
     * below correctly points at the *stream's* true frame 0 (up to 87s earlier), so every
     * PTS this encoder computed landed far in the past relative to recordingStartNanos and
     * was silently dropped by [PtsClockDomain.normalizeAudioPtsUs]'s monotonic guard until
     * enough frames drained to close that gap — which for a short recording never happens.
     *
     * Fixed by (1) flushing the stale backlog so draining starts from "now", and (2)
     * seeding [cumulativeSampleCount] from the correlation's own frame position instead of
     * 0, so it lines up with the same frame-0 the anchor below is computed against.
     */
    private fun seedAudioAnchor() {
        nativeEngine.flushRingBuffer()

        // Captured BEFORE the retry loop, not after: the ring buffer has been filling
        // since nativeEngine.start() (called by our caller before this), so if correlation
        // fails, "now" at the *start* of this method is a much closer approximation of
        // sample 0's true capture time than "now" after burning the retry budget — see the
        // real-device finding this fixes, in this method's class doc / ARCHITECTURE.md.
        val fallbackAnchorNanos = System.nanoTime()
        repeat(ANCHOR_CORRELATION_MAX_ATTEMPTS) {
            val correlation = nativeEngine.getInputTimestamp()
            if (correlation != null) {
                val (framePosition, timeNanos) = correlation
                ptsClockDomain.startAudioAnchorFromFrameCorrelation(framePosition, timeNanos, sampleRateHz)
                // Aligns this encoder's own frame counter to the stream's true position
                // (this method's doc) rather than assuming draining starts at frame 0.
                cumulativeSampleCount = framePosition
                return
            }
            Thread.sleep(ANCHOR_CORRELATION_RETRY_SLEEP_MS)
        }
        // Correlation never became available within the retry budget (e.g. the native
        // engine hasn't produced its first input callback yet) — fall back to wall-clock
        // anchoring so recording doesn't hard-fail, at the cost of reintroducing the
        // input-latency offset (see PtsClockDomain.startAudioAnchor's doc).
        // cumulativeSampleCount is left at 0 here — consistent with this fallback also
        // treating "now" (fallbackAnchorNanos) as sample 0.
        Log.w(TAG, "getInputTimestamp() never returned a correlation after " +
            "$ANCHOR_CORRELATION_MAX_ATTEMPTS attempts; falling back to wall-clock audio anchor " +
            "(A/V sync may be off by the audio input pipeline's latency)")
        ptsClockDomain.startAudioAnchor(nowNanos = fallbackAnchorNanos)
    }

    /**
     * Signals end-of-stream and waits for the drain thread to finish (§4.4's stop sequence).
     *
     * Real-device finding (Sony SO-51C): this used to call `codec.stop()`/`codec.release()`
     * itself right after a *timed* `join()`. `Thread.join(timeout)` returns whether or not
     * the thread actually finished — under load (heavy background GC, muxer lock
     * contention from [com.aucampro.recorder.muxer.SegmentedMuxerController]'s shared
     * lock — see its class doc) [drainThread]'s own post-EOS drain could still be mid
     * `queueInput()`/`dequeueInputBuffer()` when the timeout elapsed, so the caller's
     * `codec.release()` raced it and threw `IllegalStateException: codec is released
     * already` from inside [queueInput] — reproduced twice on-device (once as a full ANR,
     * since this runs on the caller's dispatcher, which for
     * [com.aucampro.recorder.pipeline.RecordingPipeline]'s current callers is the Main
     * thread). Fixed by moving `codec.stop()`/`release()` into [drainLoop]'s own `finally`
     * so only the thread that was still using the codec ever calls those on it. If
     * [drainThread] is still alive after the timeout, this simply returns without touching
     * the codec — it stays alive until the drain thread's own `finally` runs, which is a
     * resource-lifetime delay, not a crash.
     */
    fun stop() {
        running.set(false)
        drainThread?.join(DRAIN_THREAD_JOIN_TIMEOUT_MS)
        if (drainThread?.isAlive == true) {
            Log.w(TAG, "AudioEncoderDrain still running after " +
                "${DRAIN_THREAD_JOIN_TIMEOUT_MS}ms; leaving codec.stop()/release() to it " +
                "instead of racing it from here")
        }
    }

    private fun drainLoop() {
        val floatScratch = FloatArray(FRAMES_PER_BLOCK * channelCount)
        val shortScratch = ShortArray(FRAMES_PER_BLOCK * channelCount)
        val byteScratch = java.nio.ByteBuffer.allocateDirect(shortScratch.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val bufferInfo = MediaCodec.BufferInfo()

        // One drain+encode attempt. Returns false only when the ring buffer was empty (the
        // "nothing left to do right now" case both the steady-state loop and the final
        // drain below need to distinguish from "encoded a block, possibly non-monotonic").
        fun drainOneBlock(): Boolean {
            val framesRead = nativeEngine.drainEncoderBuffer(floatScratch, FRAMES_PER_BLOCK)
            if (framesRead == 0) return false
            val sampleCount = framesRead * channelCount

            // In place on the reused scratch buffers, converting only the first
            // sampleCount elements — framesRead is rarely a full FRAMES_PER_BLOCK (the
            // native ring buffer's available frames vary block to block), so a naive
            // size-matched output buffer would only ever be `shortScratch` itself on
            // the rare exact-size block; every other block previously got a throwaway
            // array that the read loop below never actually read from, silently
            // feeding the encoder stale/reused shortScratch content instead of the
            // just-converted samples (real-device finding: this is what made recorded
            // audio sound like garbled noise rather than the captured signal).
            PcmDither.floatToInt16Tpdf(floatScratch, shortScratch, sampleCount)

            val ptsUs = ptsClockDomain.normalizeAudioPtsUs(cumulativeSampleCount, sampleRateHz)
            cumulativeSampleCount += framesRead
            if (ptsUs == null) {
                // Non-monotonic (shouldn't happen for a purely-increasing counter, but
                // guarded per §4.3) — logged rather than silently dropped: this exact
                // silent path is what hid the seedAudioAnchor bug (see its doc) until a
                // real-device file-level check caught the missing audio.
                Log.w(TAG, "Audio frame dropped: PTS not monotonic (cumulativeSampleCount=$cumulativeSampleCount)")
                return true
            }

            byteScratch.clear()
            for (i in 0 until sampleCount) {
                byteScratch.putShort(shortScratch[i])
            }
            byteScratch.flip()

            queueInput(byteScratch, ptsUs, endOfStream = false)
            return true
        }

        try {
            while (running.get()) {
                drainOutputAvailable(bufferInfo)
                if (!drainOneBlock()) {
                    Thread.sleep(BUFFER_EMPTY_SLEEP_MS)
                }
            }

            // stop() flips `running` and returns immediately without waiting for the ring
            // buffer to empty — real-device finding: without this, whatever was still
            // buffered (the last ~0.1-0.5s in practice) got silently truncated instead of
            // reaching the encoder. drainOutputAvailable keeps pace alongside so this
            // doesn't exhaust MediaCodec's input buffer pool if the backlog is large.
            while (drainOneBlock()) {
                drainOutputAvailable(bufferInfo)
            }

            // Final EOS buffer, per §4.4's stop sequence (EOS -> drain all pending output -> stop/release).
            val finalPtsUs = ptsClockDomain.normalizeAudioPtsUs(cumulativeSampleCount, sampleRateHz)
                ?: (cumulativeSampleCount * 1_000_000L / sampleRateHz)
            queueInput(java.nio.ByteBuffer.allocateDirect(0), finalPtsUs, endOfStream = true)
            drainOutputUntilEos(bufferInfo)
        } catch (e: Exception) {
            callback.onError(e)
        } finally {
            // Only this thread ever calls dequeue/queueInputBuffer, so it's the only safe
            // owner of stop()/release() too — see [stop]'s doc for the cross-thread race
            // this replaced.
            codec.stop()
            codec.release()
        }
    }

    private fun queueInput(data: java.nio.ByteBuffer, ptsUs: Long, endOfStream: Boolean) {
        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0) return
        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(data)
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        codec.queueInputBuffer(inputIndex, 0, data.position(), ptsUs, flags)
    }

    private fun drainOutputAvailable(bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!formatAnnounced) {
                        formatAnnounced = true
                        callback.onOutputFormatChanged(codec.outputFormat)
                    }
                }
                outputIndex >= 0 -> emitOutput(outputIndex, bufferInfo)
                else -> return // INFO_TRY_AGAIN_LATER or INFO_OUTPUT_BUFFERS_CHANGED (deprecated path)
            }
        }
    }

    private fun drainOutputUntilEos(bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!formatAnnounced) {
                    formatAnnounced = true
                    callback.onOutputFormatChanged(codec.outputFormat)
                }
                continue
            }
            if (outputIndex < 0) continue
            emitOutput(outputIndex, bufferInfo)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    private fun emitOutput(outputIndex: Int, bufferInfo: MediaCodec.BufferInfo) {
        val buffer = codec.getOutputBuffer(outputIndex)
        if (buffer != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            callback.onEncodedFrame(buffer, bufferInfo)
        }
        codec.releaseOutputBuffer(outputIndex, false)
    }

    private companion object {
        const val TAG = "AudioEncoder"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        // 5ms rather than the original 2ms — PERF_INVESTIGATION_2026-07-17.md P7: this
        // thread runs for the whole preview/recording lifetime (not just while actively
        // draining), so its steady-state wakeup rate is a real, continuous cost. A block is
        // ~10.7ms of audio (FRAMES_PER_BLOCK @48kHz), so polling at 5ms still drains with
        // more than 2x headroom before the ring buffer could back up.
        const val BUFFER_EMPTY_SLEEP_MS = 5L
        const val DRAIN_THREAD_JOIN_TIMEOUT_MS = 3_000L

        // Drain granularity: ~10.7ms @48kHz, comfortably below the ring buffer's ~10s
        // capacity headroom (OboeFullDuplexEngine::kRingBufferCapacityFrames) and small
        // enough to keep the AAC encoder's input latency low.
        const val FRAMES_PER_BLOCK = 512

        // 200 attempts * 10ms = 2000ms budget. A 250ms budget was empirically too short
        // on real hardware (Sony SO-51C): AAudio's getTimestamp() reliably returned
        // ErrorInvalidState until the input stream had processed several bursts' worth
        // of frames, which took closer to 1s in practice — the 250ms budget was silently
        // falling back to wall-clock anchoring on every real recording (see
        // docs/ARCHITECTURE.md's judgment log), reintroducing the input-latency offset
        // this mechanism exists to remove. 2s is a one-time recording-start cost, not a
        // steady-state one, so it's an acceptable trade for reliably getting the accurate
        // anchor.
        const val ANCHOR_CORRELATION_MAX_ATTEMPTS = 200
        const val ANCHOR_CORRELATION_RETRY_SLEEP_MS = 10L
    }
}
