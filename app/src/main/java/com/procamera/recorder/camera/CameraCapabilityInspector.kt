package com.procamera.recorder.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * §1.1's device-capability gating: selects the default rear standard lens, checks HW
 * level, and verifies (via MediaCodecList, not just INFO_SUPPORTED_HARDWARE_LEVEL) that
 * a given resolution/fps/bitrate combination is actually encodable before committing to
 * it — FULL hardware level does not guarantee a specific codec configuration is
 * supported.
 */
class CameraCapabilityInspector(private val cameraManager: CameraManager) {

    data class LensInfo(
        val cameraId: String,
        val focalLengthMm: Float,
        val isStandardRearLens: Boolean,
    )

    private data class RearCandidate(
        val cameraId: String,
        val focalLengthMm: Float,
        val equivalentFocalLengthMm: Float,
        val isLogicalMultiCamera: Boolean,
        val hardwareLevel: Int,
        val sensorDiagonalMm: Float,
    )

    /**
     * Enumerates back-facing lenses and identifies the "standard" one — i.e. not the
     * ultra-wide, telephoto, or an auxiliary macro/depth sensor on multi-lens devices —
     * rather than assuming array index 0 (§1.1's explicit requirement, since HAL lens
     * ordering is not a documented contract).
     *
     * **実機で修正済み(確信度の教訓)**: 当初は「35mm換算焦点距離が28mmに最も近いレンズ」
     * という素朴なヒューリスティックのみで選定していたが、実機(Sony SO-51C)で
     * **誤って超小型センサーの補助レンズ(センサー対角線3.0mm、焦点距離2.14mm)を
     * 「標準」として選んでしまうバグを実際に確認した** — 極小センサー×極短焦点距離の
     * 組み合わせが偶然28mm付近の換算値を生んだため。この失敗を受け、
     * `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`(Android公式が「アプリは
     * これをデフォルトのメインカメラとして使うべき」と意図して用意している、より
     * 直接的なシグナル)を最優先の判定基準とし、極小センサー(対角線5mm未満、
     * コンパクトデジカメの1/2.3型センサーの対角線ですら約7.7mmある水準)を候補から
     * 除外したうえで、焦点距離ヒューリスティックとHWレベルを副次的なタイブレークに
     * 降格した。それでも複数OEMでの網羅的な実機検証(Phase5)なしに全機種での
     * 正しさを断定はできない。
     */
    fun findStandardRearLens(): LensInfo? {
        val candidates = cameraManager.cameraIdList.mapNotNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null

            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLength = focalLengths?.minOrNull()
            if (focalLength == null || sensorSize == null) return@mapNotNull null

            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isLogicalMultiCamera = capabilities?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
            ) == true

            // 35mm-equivalent focal length: physical_focal_length * (43.27mm / sensor_diagonal_mm),
            // 43.27mm being the diagonal of a full-frame (36x24mm) sensor.
            val sensorDiagonalMm = kotlin.math.hypot(sensorSize.width, sensorSize.height)
            val equivalentFocalLengthMm = focalLength * (FULL_FRAME_DIAGONAL_MM / sensorDiagonalMm)

            RearCandidate(
                cameraId = id,
                focalLengthMm = focalLength,
                equivalentFocalLengthMm = equivalentFocalLengthMm,
                isLogicalMultiCamera = isLogicalMultiCamera,
                hardwareLevel = hardwareLevel(id),
                sensorDiagonalMm = sensorDiagonalMm,
            )
        }.filter { it.sensorDiagonalMm >= MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM }

        if (candidates.isEmpty()) return null

        val standard = candidates
            .sortedWith(
                compareByDescending<RearCandidate> { it.isLogicalMultiCamera }
                    .thenByDescending { it.hardwareLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL }
                    .thenBy { kotlin.math.abs(it.equivalentFocalLengthMm - NORMAL_LENS_EQUIVALENT_MM) },
            )
            .first()

        return LensInfo(
            cameraId = standard.cameraId,
            focalLengthMm = standard.focalLengthMm,
            isStandardRearLens = true,
        )
    }

    fun hardwareLevel(cameraId: String): Int {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
    }

    fun isFullOrBetter(cameraId: String): Boolean {
        return when (hardwareLevel(cameraId)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3,
            -> true
            else -> {
                // LEVEL_EXTERNAL is a value >= FULL numerically on some API levels only
                // from API 30; guard explicitly rather than relying on numeric ordering.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    hardwareLevel(cameraId) == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL
            }
        }
    }

    fun timestampSource(cameraId: String): Int {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ?: CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
    }

    /**
     * §1.1's explicit requirement: `INFO_SUPPORTED_HARDWARE_LEVEL=FULL` does not
     * guarantee a specific resolution/fps/bitrate/codec combination is actually
     * supported. Checks the real encoder capability via `MediaCodecList` rather than
     * inferring it from the camera's hardware level.
     */
    fun isVideoConfigSupported(
        mimeType: String,
        width: Int,
        height: Int,
        frameRate: Int,
        bitrate: Int,
    ): Boolean {
        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecName = codecList.findEncoderForFormat(format) ?: return false

        val codecInfo = codecList.codecInfos.firstOrNull { it.name == codecName } ?: return false
        val videoCapabilities = codecInfo.getCapabilitiesForType(mimeType).videoCapabilities ?: return false

        if (!videoCapabilities.isSizeSupported(width, height)) return false
        if (!videoCapabilities.getSupportedFrameRatesFor(width, height).contains(frameRate.toDouble())) return false
        if (!videoCapabilities.bitrateRange.contains(bitrate)) return false
        return true
    }

    /**
     * §1.2's primary target with the §1.2 fallback, expressed as an ordered list:
     * callers should try each in order and use the first one [isVideoConfigSupported]
     * accepts.
     */
    fun videoConfigCandidates(): List<VideoConfigCandidate> = listOf(
        VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160, 30, 50_000_000),
        VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080, 60, 20_000_000),
    )

    data class VideoConfigCandidate(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val bitrate: Int,
    )

    /**
     * §4.1's `INFO_SUPPORTED_HARDWARE_LEVEL_FULL` gate: on LIMITED devices, manual WB
     * (COLOR_CORRECTION_MODE_TRANSFORM_MATRIX) support is not guaranteed and the UI
     * should grey it out. Checks the actual advertised capability rather than inferring
     * it purely from hardware level, since some LIMITED devices do advertise it.
     *
     * **実機で修正済み**: 当初 `CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES` という
     * キーを使っていたが、これは実機(Sony SO-51C, Android 14 / API 34)で
     * `NoSuchFieldError` を起こしてクラッシュした — compileSdk 36 の android.jar には
     * このシンボルが存在しコンパイルは通ったが、実行時の端末側 framework.jar には
     * 存在しない(このキー自体が実在しないか、API34より新しいAPIでのみ有効という
     * ことを実機で確認)。API21から一貫して存在する
     * `REQUEST_AVAILABLE_CAPABILITIES` の `MANUAL_POST_PROCESSING` ケイパビリティで
     * 判定する、より安全な方式に置き換えた。
     */
    fun supportsManualWhiteBalance(cameraId: String): Boolean {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val availableAwbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        val availableCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val hasAwbOff = availableAwbModes?.contains(CameraMetadata.CONTROL_AWB_MODE_OFF) == true
        val hasManualPostProcessing = availableCapabilities?.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING,
        ) == true
        return hasAwbOff && hasManualPostProcessing
    }

    private companion object {
        const val FULL_FRAME_DIAGONAL_MM = 43.27f
        const val NORMAL_LENS_EQUIVALENT_MM = 28f // typical rear "main" lens equivalent on phones

        // A real 1/2.3" compact-camera sensor (common "small sensor" reference point) has
        // a ~7.7mm diagonal; this is set well below that specifically to exclude the tiny
        // auxiliary sensors (depth/macro, often ~3mm diagonal) that caused the real-device
        // misdetection documented above, without being so aggressive it could exclude a
        // legitimate small main sensor on a budget device.
        const val MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM = 5.0f
    }
}
