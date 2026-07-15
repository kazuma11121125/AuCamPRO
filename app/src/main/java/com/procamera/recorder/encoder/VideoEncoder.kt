package com.procamera.recorder.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.procamera.recorder.muxer.PtsClockDomain
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
) {
    interface Callback {
        fun onOutputFormatChanged(format: MediaFormat)
        fun onEncodedFrame(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo)
        fun onError(exception: Exception)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(mimeType)
    val inputSurface: Surface
    private val eosLatch = CountDownLatch(1)

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

                val normalizedPtsUs = ptsClockDomain.normalizeVideoPtsUs(info.presentationTimeUs * 1000L)
                if (normalizedPtsUs == null) {
                    // Dropped: non-monotonic (§4.3's guard). The Muxer's
                    // pending-queue-before-start behavior (§4.4) is what's expected to
                    // absorb any resulting gap at the very start of recording.
                    codec.releaseOutputBuffer(index, false)
                    if (isEndOfStream) eosLatch.countDown()
                    return
                }

                val adjustedInfo = MediaCodec.BufferInfo().apply {
                    set(info.offset, info.size, normalizedPtsUs, info.flags)
                }
                callback.onEncodedFrame(buffer, adjustedInfo)
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
            inputSurface = codec.createInputSurface()
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
        inputSurface.release()
    }
}
