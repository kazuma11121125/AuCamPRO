package com.procamera.recorder.pipeline

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.RequiresPermission
import com.procamera.recorder.audio.NativeEngineBridge
import com.procamera.recorder.camera.CameraCapabilityInspector
import com.procamera.recorder.camera.CameraSessionController
import com.procamera.recorder.camera.ManualCaptureRequestFactory
import com.procamera.recorder.encoder.AudioEncoder
import com.procamera.recorder.encoder.VideoEncoder
import com.procamera.recorder.muxer.PtsClockDomain
import com.procamera.recorder.muxer.SegmentedMuxerController
import java.io.File
import java.nio.ByteBuffer

/**
 * Wires Camera2 capture -> [VideoEncoder]/[AudioEncoder] -> [SegmentedMuxerController]
 * into a single start()/stop() pipeline (§4). This is deliberately the FIRST piece of
 * Phase 4 built (see docs/ARCHITECTURE.md's Phase4 note): everything underneath this
 * class had been compile-verified only and had never produced a single real file, so the
 * record-pipeline smoke test milestone exists to prove it works on a physical device
 * before UI/Service/thermal work is layered on top.
 *
 * Not yet integrated with the Foreground Service or a full permission-request UI flow —
 * the caller is responsible for having already granted CAMERA/RECORD_AUDIO before calling
 * [start]; that wiring lands once this pipeline is proven.
 */
class RecordingPipeline(private val context: Context) {

    sealed interface Event {
        data class Started(val outputDirectory: File) : Event
        data class Failed(val message: String) : Event
        data object Stopped : Event
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val capabilityInspector = CameraCapabilityInspector(cameraManager)
    private val sessionController = CameraSessionController(cameraManager)
    private val ptsClockDomain = PtsClockDomain()
    private val nativeEngine = NativeEngineBridge()

    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var muxerController: SegmentedMuxerController? = null

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    suspend fun start(onEvent: (Event) -> Unit) {
        try {
            val lens = capabilityInspector.findStandardRearLens()
                ?: error("No standard rear lens found on this device")
            val videoConfig = capabilityInspector.videoConfigCandidates().firstOrNull {
                capabilityInspector.isVideoConfigSupported(it.mimeType, it.width, it.height, it.frameRate, it.bitrate)
            } ?: error("No supported video config (checked HEVC 4K30 and H.264 1080p60 fallback)")

            Log.i(TAG, "Using cameraId=${lens.cameraId} videoConfig=$videoConfig")

            ptsClockDomain.start()

            val outputDir = File(context.getExternalFilesDir(null), "recordings/${System.currentTimeMillis()}")
            outputDir.mkdirs()

            val muxer = SegmentedMuxerController(
                outputPathForSegment = { index -> File(outputDir, "segment_$index.mp4").absolutePath },
            )
            muxerController = muxer

            val video = VideoEncoder(
                mimeType = videoConfig.mimeType,
                width = videoConfig.width,
                height = videoConfig.height,
                frameRate = videoConfig.frameRate,
                bitrate = videoConfig.bitrate,
                ptsClockDomain = ptsClockDomain,
                callback = object : VideoEncoder.Callback {
                    override fun onOutputFormatChanged(format: MediaFormat) = muxer.onVideoFormatChanged(format)
                    override fun onEncodedFrame(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) =
                        muxer.onVideoSample(buffer, bufferInfo)

                    override fun onError(exception: Exception) {
                        Log.e(TAG, "VideoEncoder error", exception)
                        onEvent(Event.Failed("VideoEncoder: ${exception.message}"))
                    }
                },
            )
            videoEncoder = video

            val engineError = nativeEngine.start()
            if (engineError != null) error("Audio engine failed to start: $engineError")

            val audio = AudioEncoder(
                sampleRateHz = AUDIO_SAMPLE_RATE_HZ,
                channelCount = AUDIO_CHANNEL_COUNT,
                bitrate = AUDIO_BITRATE_BPS,
                nativeEngine = nativeEngine,
                ptsClockDomain = ptsClockDomain,
                callback = object : AudioEncoder.Callback {
                    override fun onOutputFormatChanged(format: MediaFormat) = muxer.onAudioFormatChanged(format)
                    override fun onEncodedFrame(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) =
                        muxer.onAudioSample(buffer, bufferInfo)

                    override fun onError(exception: Exception) {
                        Log.e(TAG, "AudioEncoder error", exception)
                        onEvent(Event.Failed("AudioEncoder: ${exception.message}"))
                    }
                },
            )
            audioEncoder = audio

            video.start()
            // AudioEncoder.start() seeds PtsClockDomain's audio anchor itself (retrying
            // NativeEngineBridge.getInputTimestamp() for frame-correlation accuracy — see
            // its doc), so nativeEngine.start() above must complete before this call.
            audio.start()

            val characteristics = sessionController.characteristicsFor(lens.cameraId)
            val requestFactory = ManualCaptureRequestFactory(characteristics)
            sessionController.startRepeating(
                cameraId = lens.cameraId,
                outputSurface = video.inputSurface,
                requestFactory = requestFactory,
                iso = DEFAULT_ISO,
                exposureTimeNanos = DEFAULT_EXPOSURE_TIME_NANOS,
                fps = videoConfig.frameRate,
                focusDistanceDiopters = DEFAULT_FOCUS_DISTANCE_DIOPTERS,
                kelvin = DEFAULT_WHITE_BALANCE_KELVIN,
            )

            onEvent(Event.Started(outputDir))
        } catch (e: Exception) {
            Log.e(TAG, "RecordingPipeline start failed", e)
            onEvent(Event.Failed(e.message ?: e.toString()))
            stop()
        }
    }

    /**
     * §4.4's stop sequence: stop BOTH sources from capturing new content essentially
     * back-to-back (camera session close, then [NativeEngineBridge.stop] — see its
     * native-side doc: closing the Oboe input stream does NOT clear the ring buffer, so
     * whatever audio was already captured remains drainable below), THEN independently
     * flush each encoder's already-buffered content, and only once both are fully
     * drained close the muxer(s).
     *
     * **実機で修正済み**: 当初はカメラ停止→Video EOS完全ドレイン→AudioEncoder停止→
     * `nativeEngine.stop()`という順序だったため、Video側が停止済みでもマイクは
     * ずっと録り続け、Videoのドレイン(HEVCエンコーダのパイプライン遅延分)が完了する
     * までの間ずっと本物の音声が録られ続けていた。実機録画で検証したところ、
     * 停止ボタン押下後もVideoトラックとAudioトラックの終端に**約3.76秒**もの
     * ズレ(Audioだけが長く続く無音でない実音声の尾)が生じていた。両ソースを
     * ほぼ同時に停止するよう順序を変更し解消。
     */
    fun stop() {
        sessionController.stop()
        nativeEngine.stop()

        videoEncoder?.let { encoder ->
            encoder.signalEndOfStream()
            encoder.awaitEndOfStream()
            encoder.stop()
        }
        audioEncoder?.stop()
        muxerController?.stop()

        videoEncoder = null
        audioEncoder = null
        muxerController = null
    }

    /** Call once, when this pipeline instance will never be reused (e.g. Service teardown). */
    fun release() {
        stop()
        sessionController.release()
        nativeEngine.close()
    }

    private companion object {
        const val TAG = "RecordingPipeline"

        // Must match app/src/main/cpp/engine/OboeFullDuplexEngine.h's kSampleRate/kChannelCount.
        const val AUDIO_SAMPLE_RATE_HZ = 48_000
        const val AUDIO_CHANNEL_COUNT = 2
        const val AUDIO_BITRATE_BPS = 256_000 // §4: "AAC-LC 48kHz Stereo 256kbps"

        // Smoke-test defaults (§4.1 manual control) until Phase4's UI controls exist.
        const val DEFAULT_ISO = 400
        const val DEFAULT_EXPOSURE_TIME_NANOS = 1_000_000_000L / 60 // 1/60s
        const val DEFAULT_FOCUS_DISTANCE_DIOPTERS = 0f // 0 diopters = focus at infinity
        const val DEFAULT_WHITE_BALANCE_KELVIN = 5500.0 // daylight
    }
}
