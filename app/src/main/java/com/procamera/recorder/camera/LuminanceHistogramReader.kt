package com.procamera.recorder.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * A small, low-frame-rate luminance histogram sampler — Sony Photo/Video Pro calls this
 * the "ヒストグラム(輝度分布グラフ)" UI-assist feature (Sony__________.pdf). The existing
 * preview `SurfaceView` is a zero-copy target with no CPU access to pixel data (see
 * `PreviewSurfaceView`'s doc), so this adds a *separate*, deliberately tiny extra camera
 * output stream purely to read luma bytes back on the CPU — advisor-flagged real cost of a
 * 3rd/4th concurrent stream alongside 4K recording (ISP bandwidth, heat), mitigated two
 * ways:
 * - **Small**: picks the smallest `YUV_420_888` size the camera advertises (see
 *   [smallestYuvSize]) rather than anything close to preview/recording resolution.
 * - **Low rate**: every [Image] that arrives is closed immediately regardless, but the
 *   actual per-pixel histogram computation — the CPU-costly part — only runs on roughly
 *   1-in-N of them (see [SAMPLE_EVERY_NTH_FRAME]), not every single frame at the camera's
 *   full capture rate.
 *
 * Deliberately **not** wired into the recording session (only the plain preview session —
 * see `RecordingPipeline.startPreview`'s call site): adding a stream to the already
 * carefully-tuned preview+encoder recording combo was judged not worth the risk to that
 * path for a feature that's primarily useful for checking exposure *before* hitting REC
 * anyway. The histogram simply stops updating (freezes on its last value) once a recording
 * starts.
 */
class LuminanceHistogramReader(
    width: Int,
    height: Int,
    private val onHistogramUpdated: (FloatArray) -> Unit,
) : AutoCloseable {
    private val handlerThread = HandlerThread("HistogramReader").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_IMAGES)
    private var frameCounter = 0

    val surface: Surface = imageReader.surface

    init {
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                frameCounter++
                if (frameCounter % SAMPLE_EVERY_NTH_FRAME == 0) {
                    onHistogramUpdated(computeLumaHistogram(image))
                }
            } finally {
                image.close()
            }
        }, handler)
    }

    override fun close() {
        imageReader.close()
        handlerThread.quitSafely()
    }

    companion object {
        private const val MAX_IMAGES = 2
        private const val SAMPLE_EVERY_NTH_FRAME = 6 // ~5Hz at a 30fps preview stream
        const val HISTOGRAM_BIN_COUNT = 64

        /** Smallest `YUV_420_888` size this camera advertises, or null if it advertises none
         * (shouldn't happen for any camera that supports preview at all, but this feature
         * degrades to "no histogram" rather than crashing if it does). */
        fun smallestYuvSize(characteristics: CameraCharacteristics): android.util.Size? {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                as StreamConfigurationMap?
            return map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.minByOrNull { it.width.toLong() * it.height.toLong() }
        }

        /** Downsamples the Y (luma) plane into a [HISTOGRAM_BIN_COUNT]-bucket histogram,
         * normalized to [0, 1] against its own tallest bucket (not absolute pixel count,
         * which would vary with the reader's resolution) — a plain luminance distribution,
         * matching Sony's own "輝度分布グラフ" framing rather than per-channel RGB. */
        private fun computeLumaHistogram(image: android.media.Image): FloatArray {
            val yPlane = image.planes[0]
            val buffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val width = image.width
            val height = image.height

            val counts = IntArray(HISTOGRAM_BIN_COUNT)
            // Sparse-sample every 2nd row/column too — this reader is already tiny
            // (smallestYuvSize), so this is a second, cheap layer of throttling on top of
            // the frame-rate throttling above, not load-bearing on its own.
            var row = 0
            while (row < height) {
                var col = 0
                val rowOffset = row * rowStride
                while (col < width) {
                    val luma = buffer.get(rowOffset + col * pixelStride).toInt() and 0xFF
                    val bin = (luma * HISTOGRAM_BIN_COUNT / 256).coerceIn(0, HISTOGRAM_BIN_COUNT - 1)
                    counts[bin]++
                    col += 2
                }
                row += 2
            }

            val maxCount = counts.maxOrNull()?.takeIf { it > 0 } ?: 1
            return FloatArray(HISTOGRAM_BIN_COUNT) { i -> counts[i].toFloat() / maxCount }
        }
    }
}
