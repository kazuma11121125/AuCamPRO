package com.aucampro.recorder.camera

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
        }

        // The AVD's emulated camera HAL reports an implausibly tiny sensor (observed:
        // 3.2x2.4mm, diagonal 4.0mm on the API34 google_apis x86_64 image) that fails the
        // real-device-tuned plausibility floor below, which would otherwise make this
        // return null on every emulator and block all camera-pipeline testing there. Only
        // fall back to skipping the floor when isPlausibleFloorFiltered leaves nothing AND
        // we're actually on an emulator — real devices always go through the strict path.
        val plausible = candidates.filter { it.sensorDiagonalMm >= MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM }
        val filtered = if (plausible.isEmpty() && isRunningOnEmulator()) candidates else plausible

        if (filtered.isEmpty()) return null

        val standard = filtered
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
     *
     * **Not the fps-ceiling fix**: a real-device investigation on 2026-07-20 initially
     * added a `StreamConfigurationMap.getOutputMinFrameDuration()`-based session check
     * here, on the theory that the *session* (not the encoder) couldn't sustain the
     * requested fps. That check was removed — this device's `StreamConfigurationMap`
     * reports 60fps as achievable (`getOutputMinFrameDuration` = 16.67ms for both the
     * preview and encoder surfaces), so it would have failed open (no-op) anyway. The
     * real cause (confirmed via `CaptureResult.SENSOR_EXPOSURE_TIME`/
     * `SENSOR_FRAME_DURATION` on-device) was a stale/persisted shutter-speed setting
     * (~1/33s) independently forcing `SENSOR_FRAME_DURATION` up to match it — Camera2
     * always requires `frameDuration >= exposureTime`. Fixed at
     * `CameraControlViewModel.selectVideoConfig()` (clamps exposure time to the newly
     * selected fps), not here — see docs/VIDEO_FPS_STUTTER_INVESTIGATION_2026-07-20.md §2.
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

    // -----------------------------------------------------------------------------------------
    // Multi-lens enumeration (added for lens-switcher UI)
    // -----------------------------------------------------------------------------------------

    /**
     * Represents a single rear-facing physical (or logical-multi) camera available on
     * this device, enriched with the metadata the lens-switcher UI and zoom controller
     * need at startup.
     *
     * [equivalentFocalLengthMm] is the 35mm-film-equivalent focal length computed via
     * the same formula used in [findStandardRearLens]: `physicalFocalLength × (43.27 /
     * sensorDiagonalMm)`.  [zoomLabel] is the rounded 35mm-equivalent focal length itself
     * (e.g. "16mm", "28mm", "85mm") — mm rather than an "×" multiplier, per feedback from
     * real-device use: a photographer reading a lens strip thinks in focal length, not a
     * ratio against whatever the "standard" lens happens to be. [maxDigitalZoom] is read
     * directly from `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` — defaulting to 1f if absent — and
     * caps the zoom range that the pinch-to-zoom gesture exposes for this lens.
     */
    data class AvailableLens(
        val cameraId: String,
        val equivalentFocalLengthMm: Float,
        val zoomLabel: String,         // e.g. "16mm", "28mm", "85mm"
        val isStandardLens: Boolean,
        val hardwareLevel: Int,
        val maxDigitalZoom: Float,     // from SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
        val sensorDiagonalMm: Float,
    )

    /**
     * Enumerates **all** rear-facing cameras whose sensor diagonal meets the plausibility
     * floor ([MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM]), sorted by 35mm-equivalent focal
     * length ascending (ultra-wide → standard → telephoto).
     *
     * This is the source of truth for the lens-switcher strip: the UI renders one button
     * per element, highlights [AvailableLens.isStandardLens], and uses
     * [AvailableLens.zoomLabel] as the button label.
     *
     * **実機で発見・修正(2026-07-14)**: Sony Photo Pro(実機に同梱の純正アプリ、
     * `com.sonymobile.photopro`)と並べて比較したところ、Photo Proのレンズピッカーは
     * 「16mm・24mm・85-125mm」の3つのみを表示するのに対し、このメソッドは4つ
     * (「16mm・23mm・24mm・88mm」)を返していた。
     *
     * 最初に試した修正(`REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`を持つ
     * カメラの`getPhysicalCameraIds()`に含まれるIDを丸ごと除外)は実機で1枚
     * (「24mm」のみ)にまで減ってしまい過剰だった — この端末では16mm/88mmの物理レンズ
     * も何らかの論理マルチカメラの構成要素として現れるらしく、「論理カメラの構成要素は
     * 全部隠す」という判定基準では本来見せるべき独立したレンズまで消えてしまう。
     * 実際に排除すべきは「23mm」が「24mm」とほぼ同じ焦点距離の**重複**エントリだった
     * という点だけなので、[dedupeByFocalLengthCluster]で焦点距離が近い
     * ([FOCAL_LENGTH_DEDUPE_TOLERANCE_MM]以内)エントリをまとめ、各クラスタから
     * 標準レンズ(あれば)を代表として1つだけ残す方式に変更した。
     */
    fun allRearLenses(): List<AvailableLens> {
        val standardCameraId = findStandardRearLens()?.cameraId

        val allCandidates = cameraManager.cameraIdList.mapNotNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null

            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLength = focalLengths?.minOrNull()
            if (focalLength == null || sensorSize == null) return@mapNotNull null

            // Same formula as findStandardRearLens: 35mm-equivalent = focal × (43.27 / diagonal)
            val sensorDiagonalMm = kotlin.math.hypot(sensorSize.width, sensorSize.height)
            // See findStandardRearLens's isRunningOnEmulator comment: the AVD's fake sensor
            // fails this floor, so it only applies on real hardware.
            if (sensorDiagonalMm < MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM && !isRunningOnEmulator()) return@mapNotNull null

            val equivalentFocalLengthMm = focalLength * (FULL_FRAME_DIAGONAL_MM / sensorDiagonalMm)
            val zoomLabel = "%.0fmm".format(equivalentFocalLengthMm)

            AvailableLens(
                cameraId = id,
                equivalentFocalLengthMm = equivalentFocalLengthMm,
                zoomLabel = zoomLabel,
                isStandardLens = id == standardCameraId,
                hardwareLevel = hardwareLevel(id),
                maxDigitalZoom = maxDigitalZoom(id),
                sensorDiagonalMm = sensorDiagonalMm,
            )
        }.sortedBy { it.equivalentFocalLengthMm }

        return dedupeByFocalLengthCluster(allCandidates)
    }

    /**
     * Collapses lenses whose 35mm-equivalent focal lengths are within
     * [FOCAL_LENGTH_DEDUPE_TOLERANCE_MM] of each other into a single entry — see
     * [allRearLenses]'s doc for why. [candidates] must already be sorted ascending by
     * [AvailableLens.equivalentFocalLengthMm]. Within a cluster, the standard lens is kept
     * if present (it's the one the rest of the app treats as the default/fallback), else
     * the first (arbitrary but deterministic).
     */
    private fun dedupeByFocalLengthCluster(candidates: List<AvailableLens>): List<AvailableLens> {
        val result = mutableListOf<AvailableLens>()
        var clusterStart = 0
        while (clusterStart < candidates.size) {
            var clusterEnd = clusterStart
            while (clusterEnd + 1 < candidates.size &&
                candidates[clusterEnd + 1].equivalentFocalLengthMm - candidates[clusterStart].equivalentFocalLengthMm
                <= FOCAL_LENGTH_DEDUPE_TOLERANCE_MM
            ) {
                clusterEnd++
            }
            val cluster = candidates.subList(clusterStart, clusterEnd + 1)
            result += cluster.firstOrNull { it.isStandardLens } ?: cluster.first()
            clusterStart = clusterEnd + 1
        }
        return result
    }

    /**
     * Returns every [VideoConfigCandidate] from the full candidate list that
     * [isVideoConfigSupported] confirms is actually encodable on this device, sorted
     * by descending pixel count (4K before 1080p, 1080p60 before 1080p30, etc.).
     *
     * The full candidate set checked (superset of [videoConfigCandidates]):
     * - HEVC 4K 3840×2160 30fps 50 Mbps
     * - HEVC 4K 3840×2160 24fps 40 Mbps
     * - AVC 1080p 1920×1080 60fps 20 Mbps
     * - AVC 1080p 1920×1080 30fps 10 Mbps
     * - AVC 720p 1280×720  60fps  8 Mbps
     *
     * [cameraId] is accepted as a parameter for future use (e.g. per-camera codec
     * capability queries via `CameraCharacteristics`) but the current implementation
     * delegates entirely to the device-global [isVideoConfigSupported] check, which is
     * correct for MediaCodec availability.
     *
     * **16:9-only, by design**: every candidate below shares the fixed preview buffer's
     * 16:9 shape (see [com.aucampro.recorder.ui.components.PreviewSurfaceView]'s doc for
     * why that buffer is fixed). Non-16:9 candidates (21:9 2560×1080, 4:3 1440×1080, 1:1
     * 1080×1080) used to be offered here and were removed **実機で発見(2026-07-16)**:
     * recording at any of them added a differently-shaped encoder stream alongside the
     * 16:9 preview stream in the same Camera2 session, and the crop-region/sensor-readout
     * recomputation needed to satisfy both stream shapes at once visibly shifted the
     * preview's FOV and re-triggered AF hunting at the moment the record button was
     * pressed. A same-aspect-ratio preview was tried as a fix (matching the encoder's own
     * shape instead of staying 16:9) but made things categorically worse: this device's
     * Camera2 HAL rejects the resulting *two-non-16:9-PRIVATE-stream* session outright
     * (`CameraCaptureSession.StateCallback#onConfigureFailed`, "Unsupported set of
     * inputs/outputs provided") — confirmed both when reusing the encoder's exact
     * resolution as the preview size AND when substituting a different, independently
     * `dumpsys media.camera`-confirmed same-aspect-ratio preview size (960×720 for 4:3).
     * The only session combination this HAL accepts is 16:9-preview + non-16:9-encoder,
     * which is exactly what caused the original FOV/AF-hunt bug — so on this device
     * there's no preview-buffer trick that both keeps non-16:9 recording and avoids the
     * artifact. Removing the non-16:9 options is the trade-off actually made: simpler than
     * eagerly holding the encoder surface in the session from preview-start to avoid the
     * record-time rebuild entirely (which stayed unexplored as bigger in scope). If
     * non-16:9 recording is wanted again later, re-verify each removed candidate's session
     * combo on real hardware before restoring it — don't just uncomment these lines.
     */
    @Suppress("UNUSED_PARAMETER") // cameraId reserved for future per-camera codec queries
    fun supportedVideoConfigs(cameraId: String): List<VideoConfigCandidate> {
        val allCandidates = listOf(
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160, 30, 50_000_000), // 16:9
            // 3840x2880 (4:3 4K) intentionally NOT offered: **実機で発見(2026-07-14)** —
            // this device (Sony SO-51C) rejects the Camera2 session outright
            // (CameraCaptureSession.StateCallback#onConfigureFailed, "Unsupported set of
            // inputs/outputs provided") when this is paired with the fixed 16:9 1920x1080
            // preview buffer (see PreviewSurfaceView's doc for why that buffer is fixed).
            // Its total pixel count (11.06MP) also exceeds the 16:9 4K option's (8.29MP),
            // so supportedVideoConfigs()'s descending-pixel-count sort put it FIRST — every
            // fresh install/preference-reset silently defaulted to a resolution that can
            // never actually record. Root cause traced via `dumpsys`/logcat on-device, not
            // reproducible from a host build.
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC,  1920, 1080, 60, 20_000_000), // 16:9
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC,  1920, 1080, 30, 10_000_000), // 16:9
            // 1080p24 — added 2026-07-20 for users who want more headroom below the ~33fps
            // session ceiling than 30fps already gives (docs/
            // VIDEO_FPS_STUTTER_INVESTIGATION_2026-07-20.md). Confirmed [24, 24] is one of
            // this device's actual CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES entries, so
            // ExposureMode.AUTO can select it exactly (not just fall back to HAL default).
            // Bitrate scaled down from 1080p30's 10Mbps by the fps ratio (24/30).
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC,  1920, 1080, 24,  8_000_000), // 16:9
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC,  1280,  720, 60,  8_000_000), // 16:9
        )
        return allCandidates
            .filter { c ->
                isVideoConfigSupported(c.mimeType, c.width, c.height, c.frameRate, c.bitrate)
            }
            .sortedByDescending { it.width * it.height }
    }

    /**
     * Reads `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` for [cameraId], returning 1f if the
     * characteristic is absent (meaning no digital zoom beyond 1× is advertised).
     *
     * This value caps the zoom ratio exposed by the pinch-to-zoom gesture for this
     * physical lens. The camera2 API contract guarantees the crop region covers the full
     * sensor at ratio 1× and is cropped down to `1 / maxDigitalZoom` of the sensor at
     * the maximum zoom level.
     */
    fun maxDigitalZoom(cameraId: String): Float {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    }

    /**
     * Standard `Build` fingerprint heuristics for "is this the Android emulator" (the same
     * signals used by, e.g., Firebase/Play Integrity emulator detection). Only used to relax
     * the sensor-plausibility floor above, which is tuned against real hardware and always
     * false-positives on the AVD's synthetic camera HAL — never gates any user-facing
     * recording behavior.
     */
    private fun isRunningOnEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.PRODUCT.contains("sdk") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")

    private companion object {
        const val FULL_FRAME_DIAGONAL_MM = 43.27f
        const val NORMAL_LENS_EQUIVALENT_MM = 28f // typical rear "main" lens equivalent on phones

        // See allRearLenses()/dedupeByFocalLengthCluster's doc: real-device duplicate was
        // "23mm" vs "24mm" (1mm apart) for what Sony's own Photo Pro treats as one lens.
        // 3mm comfortably merges that without risking merging genuinely distinct lenses,
        // which on every real multi-lens phone checked so far are spaced much further apart
        // (e.g. 16mm ultra-wide vs 24mm main is an 8mm gap).
        const val FOCAL_LENGTH_DEDUPE_TOLERANCE_MM = 3f

        // A real 1/2.3" compact-camera sensor (common "small sensor" reference point) has
        // a ~7.7mm diagonal; this is set well below that specifically to exclude the tiny
        // auxiliary sensors (depth/macro, often ~3mm diagonal) that caused the real-device
        // misdetection documented above, without being so aggressive it could exclude a
        // legitimate small main sensor on a budget device.
        const val MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM = 5.0f
    }
}
