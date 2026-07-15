package com.procamera.recorder.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.procamera.recorder.audio.AudioDeviceRouter
import com.procamera.recorder.ui.viewmodel.CameraUiState
import com.procamera.recorder.ui.viewmodel.EqBandState
import com.procamera.recorder.ui.viewmodel.FrameLineAspectRatio
import com.procamera.recorder.ui.viewmodel.StorageLocation

/**
 * Plain `SharedPreferences` (not DataStore — nothing else in this project pulls in that
 * dependency, and this is a handful of primitives read once at startup and written on a
 * debounce, where DataStore's async Flow API would be pure overhead) persistence of the
 * settings a photographer re-dials in every session: lens, zoom, ISO, shutter, WB/focus
 * mode, frame-line guide, and the audio input gain/EQ/monitor settings. Real-device
 * feedback: re-setting all of this on every single launch was the actual friction point,
 * not anything more elaborate — so this persists exactly what [CameraControlViewModel]
 * restores on the next launch and nothing derived/per-recording (capabilities, elapsed
 * time, meter levels, etc. all still come fresh from hardware each launch).
 */
class UserPreferencesStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class EqBandSaved(val freqHz: Float?, val q: Float?, val gainDb: Float?)

    data class Saved(
        val lensCameraId: String?,
        val iso: Int?,
        val exposureTimeNanos: Long?,
        val zoomRatio: Float?,
        val kelvin: Double?,
        val wbAuto: Boolean,
        val afAuto: Boolean,
        val frameLineAspectRatio: FrameLineAspectRatio,
        val audioInputPreference: AudioDeviceRouter.InputKind,
        val inputGainDb: Float,
        val monitoringEnabled: Boolean,
        val storageLocation: StorageLocation,
        val segmentDurationMinutes: Int?,
        val videoConfigWidth: Int?,
        val videoConfigHeight: Int?,
        val videoConfigFps: Int?,
        val eqBands: List<EqBandSaved>,
    )

    fun load(): Saved = Saved(
        lensCameraId = prefs.getString(KEY_LENS_CAMERA_ID, null),
        iso = prefs.getInt(KEY_ISO, -1).takeIf { it > 0 },
        exposureTimeNanos = prefs.getLong(KEY_EXPOSURE_NANOS, -1L).takeIf { it > 0 },
        zoomRatio = prefs.getFloat(KEY_ZOOM_RATIO, -1f).takeIf { it > 0f },
        kelvin = prefs.getFloat(KEY_KELVIN, -1f).takeIf { it > 0f }?.toDouble(),
        wbAuto = prefs.getBoolean(KEY_WB_AUTO, true),
        afAuto = prefs.getBoolean(KEY_AF_AUTO, true),
        frameLineAspectRatio = prefs.getString(KEY_FRAME_LINE, null)
            ?.let { name -> FrameLineAspectRatio.entries.firstOrNull { it.name == name } }
            ?: FrameLineAspectRatio.Off,
        audioInputPreference = prefs.getString(KEY_AUDIO_INPUT_PREFERENCE, null)
            ?.let { name -> AudioDeviceRouter.InputKind.entries.firstOrNull { it.name == name } }
            ?: AudioDeviceRouter.InputKind.Auto,
        inputGainDb = prefs.getFloat(KEY_INPUT_GAIN, 0f),
        monitoringEnabled = prefs.getBoolean(KEY_MONITORING, false),
        storageLocation = loadStorageLocation(),
        segmentDurationMinutes = prefs.getInt(KEY_SEGMENT_MINUTES, -1).takeIf { it > 0 },
        videoConfigWidth = prefs.getInt(KEY_VIDEO_W, -1).takeIf { it > 0 },
        videoConfigHeight = prefs.getInt(KEY_VIDEO_H, -1).takeIf { it > 0 },
        videoConfigFps = prefs.getInt(KEY_VIDEO_FPS, -1).takeIf { it > 0 },
        eqBands = (0 until EQ_BAND_COUNT).map { i ->
            EqBandSaved(
                freqHz = prefs.getFloat(eqKey(i, "freq"), -1f).takeIf { it > 0f },
                q = prefs.getFloat(eqKey(i, "q"), -1f).takeIf { it > 0f },
                gainDb = prefs.getFloat(eqKey(i, "gain"), Float.NaN).takeUnless { it.isNaN() },
            )
        },
    )

    private fun loadStorageLocation(): StorageLocation {
        return when (prefs.getString(KEY_STORAGE_LOCATION_KIND, null)) {
            "PublicMovies" -> StorageLocation.PublicMovies
            "Custom" -> {
                val uriString = prefs.getString(KEY_STORAGE_LOCATION_URI, null)
                val displayPath = prefs.getString(KEY_STORAGE_LOCATION_DISPLAY, null)
                if (uriString != null && displayPath != null) {
                    StorageLocation.Custom(Uri.parse(uriString), displayPath)
                } else {
                    StorageLocation.AppPrivate
                }
            }
            else -> StorageLocation.AppPrivate
        }
    }

    /** Call on a debounce from the ViewModel's state collector — not on every keystroke of
     * a slider drag. Uses [SharedPreferences.Editor.commit] (synchronous), not [apply]:
     * confirmed on real hardware that `apply()`'s pending async write does not survive an
     * abrupt process kill (e.g. `adb shell am force-stop`, or the OS reclaiming the process
     * under memory pressure shortly after backgrounding) — the whole point of this store is
     * surviving exactly that kind of restart, so losing the last debounce window's write
     * defeats it. This runs off the main thread already (called from a
     * viewModelScope.launch collector), so `commit()`'s synchronous I/O is not a jank risk. */
    fun save(state: CameraUiState) {
        prefs.edit().apply {
            putString(KEY_LENS_CAMERA_ID, state.selectedLensCameraId)
            putInt(KEY_ISO, state.iso)
            putLong(KEY_EXPOSURE_NANOS, state.exposureTimeNanos)
            putFloat(KEY_ZOOM_RATIO, state.zoomRatio)
            putFloat(KEY_KELVIN, state.kelvin.toFloat())
            putBoolean(KEY_WB_AUTO, state.wbAuto)
            putBoolean(KEY_AF_AUTO, state.afAuto)
            putString(KEY_FRAME_LINE, state.settings.frameLineAspectRatio.name)
            putString(KEY_AUDIO_INPUT_PREFERENCE, state.settings.audioInputPreference.name)
            putFloat(KEY_INPUT_GAIN, state.inputGainDb)
            putBoolean(KEY_MONITORING, state.monitoringEnabled)
            putInt(KEY_SEGMENT_MINUTES, state.settings.segmentDurationMinutes)

            when (val location = state.settings.storageLocation) {
                StorageLocation.AppPrivate -> putString(KEY_STORAGE_LOCATION_KIND, "AppPrivate")
                StorageLocation.PublicMovies -> putString(KEY_STORAGE_LOCATION_KIND, "PublicMovies")
                is StorageLocation.Custom -> {
                    putString(KEY_STORAGE_LOCATION_KIND, "Custom")
                    putString(KEY_STORAGE_LOCATION_URI, location.uri.toString())
                    putString(KEY_STORAGE_LOCATION_DISPLAY, location.displayPath)
                }
            }

            state.selectedVideoConfig?.let { config ->
                putInt(KEY_VIDEO_W, config.width)
                putInt(KEY_VIDEO_H, config.height)
                putInt(KEY_VIDEO_FPS, config.frameRate)
            }

            state.eqBands.take(EQ_BAND_COUNT).forEachIndexed { i, band: EqBandState ->
                putFloat(eqKey(i, "freq"), band.freqHz)
                putFloat(eqKey(i, "q"), band.q)
                putFloat(eqKey(i, "gain"), band.gainDb)
            }
        }.commit()
    }

    private companion object {
        const val PREFS_NAME = "procamera_user_prefs"
        const val EQ_BAND_COUNT = 3

        const val KEY_LENS_CAMERA_ID = "lens_camera_id"
        const val KEY_ISO = "iso"
        const val KEY_EXPOSURE_NANOS = "exposure_time_nanos"
        const val KEY_ZOOM_RATIO = "zoom_ratio"
        const val KEY_KELVIN = "kelvin"
        const val KEY_WB_AUTO = "wb_auto"
        const val KEY_AF_AUTO = "af_auto"
        const val KEY_FRAME_LINE = "frame_line_aspect_ratio"
        const val KEY_AUDIO_INPUT_PREFERENCE = "audio_input_preference"
        const val KEY_INPUT_GAIN = "input_gain_db"
        const val KEY_MONITORING = "monitoring_enabled"
        const val KEY_SEGMENT_MINUTES = "segment_duration_minutes"
        const val KEY_VIDEO_W = "video_config_width"
        const val KEY_VIDEO_H = "video_config_height"
        const val KEY_VIDEO_FPS = "video_config_fps"
        const val KEY_STORAGE_LOCATION_KIND = "storage_location_kind"
        const val KEY_STORAGE_LOCATION_URI = "storage_location_uri"
        const val KEY_STORAGE_LOCATION_DISPLAY = "storage_location_display"

        fun eqKey(bandIndex: Int, field: String) = "eq_band_${bandIndex}_$field"
    }
}
