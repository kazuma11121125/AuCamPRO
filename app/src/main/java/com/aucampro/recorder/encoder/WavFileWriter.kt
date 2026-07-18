package com.aucampro.recorder.encoder

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams interleaved 32-bit float PCM to a RIFF/WAVE file (`WAVE_FORMAT_IEEE_FLOAT`,
 * format tag 3) — the hi-res audio "サイドカー" sink (docs/HIRES_AUDIO_DESIGN.md §1/§6.2).
 * Fully lossless: the ring buffer this app's audio engine produces is already 32-bit
 * float, so this is header + memcpy, no requantization.
 *
 * The RIFF/fact/data chunk *size* fields are unknown until the stream ends (this is a
 * live stream, not a fixed-size buffer), so they're written as zero placeholders in the
 * constructor and back-patched in [close] — mirrors
 * [com.aucampro.recorder.pipeline.RecordingPipeline]'s `emergencyFinalizeCurrentSegment`
 * crash-safety pattern for the MP4/muxer side: [close] must be safe to call from a crash
 * handler mid-stream, not just after a clean stop, so the file is still a valid (if
 * truncated) WAV rather than a header full of zeros.
 *
 * Single-writer, no internal synchronization — matches [AudioEncoder]'s drain-thread
 * ownership (see its class doc on the ring buffer's single-consumer invariant): this class
 * must only ever be driven from that one thread, including [close].
 */
class WavFileWriter(
    path: String,
    private val sampleRateHz: Int,
    private val channelCount: Int,
) : AutoCloseable {
    private val file = RandomAccessFile(path, "rw")
    private var samplesWrittenPerChannel = 0L
    private var closed = false

    // Lazily sized to the caller's actual block size and kept for reuse — avoids a
    // per-write allocation (same reasoning as AudioEncoder.drainLoop's floatScratch/
    // shortScratch/byteScratch reuse) without needing the caller to declare a max block
    // size up front.
    private var scratch = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    init {
        val bitsPerSample = 32
        val byteRate = sampleRateHz * channelCount * (bitsPerSample / 8)
        val blockAlign = channelCount * (bitsPerSample / 8)

        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put(ASCII_RIFF)
        header.putInt(0) // RIFF chunk size — back-patched in close()
        header.put(ASCII_WAVE)
        header.put(ASCII_FMT)
        header.putInt(16) // fmt chunk size (no extension needed for plain IEEE float)
        header.putShort(3) // wFormatTag = WAVE_FORMAT_IEEE_FLOAT
        header.putShort(channelCount.toShort())
        header.putInt(sampleRateHz)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put(ASCII_FACT)
        header.putInt(4) // fact chunk size
        header.putInt(0) // dwSampleLength (per channel) — back-patched in close()
        header.put(ASCII_DATA)
        header.putInt(0) // data chunk size — back-patched in close()
        file.write(header.array())
    }

    /** Writes [frameCount] interleaved frames ([frameCount] * [channelCount] floats,
     * starting at index 0 of [interleaved]) to the stream. */
    fun writeFrames(interleaved: FloatArray, frameCount: Int) {
        val sampleCount = frameCount * channelCount
        val neededBytes = sampleCount * BYTES_PER_SAMPLE
        if (scratch.capacity() < neededBytes) {
            scratch = ByteBuffer.allocate(neededBytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        scratch.clear()
        for (i in 0 until sampleCount) scratch.putFloat(interleaved[i])
        file.write(scratch.array(), 0, neededBytes)
        samplesWrittenPerChannel += frameCount
    }

    /** Total `data` chunk bytes written so far — used by [HiResAudioSink] to decide when
     * to rotate to a new segment file. */
    fun dataBytesWritten(): Long = samplesWrittenPerChannel * channelCount * BYTES_PER_SAMPLE

    /** Back-patches the RIFF/fact/data size fields with the actual amount written, then
     * closes the file. Idempotent — safe to call twice (e.g. once from a crash-safety
     * finalize path and once, harmlessly, from the normal stop path that follows it). */
    override fun close() {
        if (closed) return
        closed = true
        val dataBytes = dataBytesWritten()
        val riffChunkSize = (HEADER_SIZE - 8L) + dataBytes

        file.seek(RIFF_SIZE_OFFSET)
        file.write(intLe(riffChunkSize.toInt()))
        file.seek(FACT_SAMPLE_LENGTH_OFFSET)
        file.write(intLe(samplesWrittenPerChannel.toInt()))
        file.seek(DATA_SIZE_OFFSET)
        file.write(intLe(dataBytes.toInt()))
        file.close()
    }

    private fun intLe(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private companion object {
        const val BYTES_PER_SAMPLE = 4 // 32-bit float
        const val HEADER_SIZE = 56 // RIFF(12) + fmt(24) + fact(12) + data-header(8)
        const val RIFF_SIZE_OFFSET = 4L
        const val FACT_SAMPLE_LENGTH_OFFSET = 44L
        const val DATA_SIZE_OFFSET = 52L

        val ASCII_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        val ASCII_WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
        val ASCII_FMT = "fmt ".toByteArray(Charsets.US_ASCII)
        val ASCII_FACT = "fact".toByteArray(Charsets.US_ASCII)
        val ASCII_DATA = "data".toByteArray(Charsets.US_ASCII)
    }
}
