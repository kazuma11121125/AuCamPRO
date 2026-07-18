package com.aucampro.recorder.pipeline

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.aucampro.recorder.audio.AudioDeviceRouter
import com.aucampro.recorder.audio.AudioQuality
import com.aucampro.recorder.audio.NativeEngineBridge
import com.aucampro.recorder.camera.CameraCapabilityInspector
import com.aucampro.recorder.camera.CameraParams
import com.aucampro.recorder.camera.CameraSessionController
import com.aucampro.recorder.camera.CaptureRangeClamper
import com.aucampro.recorder.camera.ColorTemperatureConverter
import com.aucampro.recorder.camera.ManualCaptureRequestFactory
import com.aucampro.recorder.encoder.AudioEncoder
import com.aucampro.recorder.encoder.HiResAudioSink
import com.aucampro.recorder.encoder.VideoEncoder
import com.aucampro.recorder.muxer.PtsClockDomain
import com.aucampro.recorder.muxer.SegmentedMuxerController
import com.aucampro.recorder.ui.viewmodel.StorageLocation
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Unified camera/audio/encode/mux pipeline for Phase 4's full UI build.
 *
 * **State machine**: IDLE → PREVIEWING ↔ RECORDING → IDLE
 *
 * - [startPreview]: Opens the camera, streams to a [Surface] (viewfinder). No encoders run.
 * - [startRecording]: Reconfigures the session to include the encoder's InputSurface alongside
 *   the preview, then starts the full encode/mux pipeline. Must be in PREVIEWING state.
 * - [stopRecording]: Drains and finalises all encoders/muxers, then restarts preview automatically.
 * - [stopAll]: Tears everything down unconditionally (call from ViewModel.onCleared).
 *
 * **Parameter updates**: [updateCameraParams] applies changes (ISO, shutter, focus, WB) to the
 * live repeating request without reopening the session.
 *
 * **Metric access**: [nativeEngine] is exposed as a `val` so the ViewModel can poll
 * [NativeEngineBridge.peakDb] / [NativeEngineBridge.rmsDb] / [NativeEngineBridge.hardwareXRunCount]
 * at 60 fps without any extra plumbing through this class.
 *
 * スモークテストマイルストーン（docs/ARCHITECTURE.md §Phase4）で動作確認済みのパイプラインを
 * ベースにし、UI / Foreground Serviceが扱える本格的な状態機械に拡張した。
 */
class RecordingPipeline(private val context: Context) {

    // ──────────────────────────────────────────────────────────────────────────────
    // Public API types
    // ──────────────────────────────────────────────────────────────────────────────

    sealed interface Event {
        /** Emitted once the muxer is open and frames are being written. */
        data class Started(val outputDirectory: File) : Event
        /**
         * Emitted when a fatal error prevents recording from starting or continuing.
         * [sessionStopped] is true when the camera session was actually torn down as part
         * of handling this failure (a mid-recording encoder error, or an exception after
         * the session was already reconfigured for recording) — the caller must call
         * [startPreview] again to get the viewfinder back, the same way it would after a
         * normal [stopRecording]. False for the early guard-clause failures (no lens/
         * capabilities/etc. yet) where the existing preview session was never touched, so
         * restarting it would just be an unnecessary session rebuild.
         */
        data class Failed(val message: String, val sessionStopped: Boolean = false) : Event
        /** Emitted after [stopRecording] successfully drains all encoders. */
        data object Stopped : Event
        /**
         * Emitted periodically during recording with the latest stats. The ViewModel
         * collects these to update its StateFlow without polling.
         */
        data class Stats(
            val dropFrameCount: Int,
            val xrunCount: Int,
            val ringBufferOverrunCount: Int,
        ) : Event
    }

    /**
     * Camera capability snapshot filled once [startPreview] succeeds. Propagated to
     * the ViewModel so the UI can set slider ranges.
     */
    data class CameraCapabilities(
        val isoRange: IntRange,
        val exposureTimeRangeNanos: LongRange,
        val minFocusDistanceDiopters: Float,
        val supportsManualWb: Boolean,
        val hardwareLevel: Int,
        val videoConfig: CameraCapabilityInspector.VideoConfigCandidate,
        val cameraId: String,
        /** `LENS_INFO_AVAILABLE_APERTURES`'s smallest (widest) value — display-only, per
         * real-device feedback (phone lenses are fixed-aperture, so there is nothing to
         * control, but the F-number itself is still useful readout for a photographer).
         * Null if the characteristic is absent. */
        val apertureFNumber: Float?,
    )

    // ──────────────────────────────────────────────────────────────────────────────
    // Infrastructure (long-lived across preview/recording cycles)
    // ──────────────────────────────────────────────────────────────────────────────

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val capabilityInspector = CameraCapabilityInspector(cameraManager)
    private val sessionController = CameraSessionController(cameraManager)
    // See DeviceOrientationTracker's doc — supports recording upright even when physically
    // held in portrait, despite this app's window being locked to sensorLandscape. Started
    // eagerly (not just while recording) so the first recording of a session already has a
    // fresh reading rather than needing to wait out OrientationEventListener's own warm-up.
    private val orientationTracker = com.aucampro.recorder.utils.DeviceOrientationTracker(context).apply { start() }

    /**
     * Guards every `sessionController.stop()` + `startRepeating()` pair (session
     * open/close/reconfigure). Without this, e.g. rapid screen lock/unlock can fire
     * overlapping [startPreview]/[detachPreviewSurface] calls whose suspend points
     * (camera open, session configure) interleave — `CameraSessionController.stop()`
     * nulls out `device`/`session` fields synchronously, so a second call's `stop()`
     * landing between a first call's `openCamera()` and `createSession()` can corrupt
     * that shared state. Not reentrant — callers must not call a `sessionMutex`-locked
     * function from inside another one's locked block (see the `*Locked` helpers below).
     */
    private val sessionMutex = Mutex()

    /**
     * Owns the coroutine that runs a mid-recording cleanup when an encoder's `onError`
     * fires from its own (non-suspend) callback thread — [stopRecording] is `suspend` and
     * needs [sessionMutex], so the callback can't just call it directly. `Main.immediate`
     * (not `Default`) because [stopRecording]'s field mutations (`pipelineState`,
     * `videoEncoder`, etc.) are plain `var`s relied on for single-thread (main) confinement
     * elsewhere in this class — same dispatcher `viewModelScope.launch{}` uses when calling
     * this normally, so this path doesn't introduce a new thread for those fields to race
     * on. `SupervisorJob` so a failure in one launched cleanup doesn't cancel this scope for
     * the next recording; cancelled in [stopAll].
     */
    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * **実機で発見**: an `AudioEncoder`/`VideoEncoder` error mid-recording used to only log
     * and notify the UI (`Event.Failed`) — the *other* encoder, the camera session, and the
     * muxer were never told to stop, so e.g. a mid-take `AudioEncoder` failure left
     * `VideoEncoder` recording on alone for the rest of the take, producing a file whose
     * video track ran far longer than its (silently truncated) audio track. Guards
     * [onEncoderError] so only the *first* error of a take triggers the notify+cleanup
     * sequence — if both encoders fail around the same time, the second is just logged.
     * Reset at the top of each [startRecording] call.
     */
    private val recordingErrorHandled = AtomicBoolean(false)

    /**
     * Common handler for both encoders' `onError`: logs, best-effort persists the
     * exception (message + full stack trace) to a small text file inside the take's own
     * [currentOutputDir] — logcat alone was not a reliable diagnostic trail for this on a
     * real device (its ring buffer had already rotated the original exception out by the
     * time this bug was investigated) — then (once per take) notifies the caller and
     * launches the *real* stop sequence on [pipelineScope] so the rest of the pipeline
     * actually stops instead of silently recording video-only.
     */
    private fun onEncoderError(source: String, exception: Exception, onEvent: (Event) -> Unit) {
        Log.e(TAG, "$source error", exception)
        try {
            currentOutputDir?.let { dir ->
                File(dir, "encoder_error.txt").writeText(
                    "time=${System.currentTimeMillis()} source=$source\n" +
                        "${thermalSnapshotText()}\n" +
                        "${exception.stackTraceToString()}\n",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "onEncoderError: failed to write diagnostic file", e)
        }
        if (recordingErrorHandled.compareAndSet(false, true)) {
            pipelineScope.launch {
                stopRecording()
                onEvent(Event.Failed("$source: ${exception.message}", sessionStopped = true))
            }
        }
    }

    /**
     * One-shot thermal/battery-temperature snapshot for [onEncoderError]'s diagnostic file
     * — user hypothesis (2026-07-16): the mid-recording audio failure might correlate with
     * device heat rather than the GAIN/MAKEUP GAIN sliders themselves. `dumpsys
     * thermalservice`'s status enum was already shown unreliable on this hardware in an
     * earlier investigation (stayed NONE through a real, severe, measured frame-rate
     * collapse — see docs/ARCHITECTURE.md's judgment log), so this also captures battery
     * temperature (`BatteryManager`, always-available, numeric) as a corroborating signal
     * independent of that enum. Read directly via [context] rather than adding a
     * [com.aucampro.recorder.utils.ThermalMonitor] dependency here — that class is for
     * ongoing UI-facing listening (owned by the ViewModel); this is a single point-in-time
     * read at the moment of failure.
     */
    private fun thermalSnapshotText(): String {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val thermalStatus = powerManager?.currentThermalStatus
        val thermalStatusText = thermalStatus?.let {
            com.aucampro.recorder.utils.ThermalMonitor.describeStatus(it)
        } ?: "unavailable"
        val headroom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            powerManager?.getThermalHeadroom(10)?.takeUnless { it.isNaN() }
        } else {
            null
        }
        val batteryIntent = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        val tenthsOfCelsius = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val batteryTempText = if (tenthsOfCelsius >= 0) "${tenthsOfCelsius / 10.0}°C" else "unavailable"
        return "thermalStatus=$thermalStatusText thermalHeadroom10s=${headroom ?: "n/a"} batteryTemp=$batteryTempText"
    }

    /**
     * Exposed so the ViewModel can poll [NativeEngineBridge.peakDb]/[NativeEngineBridge.rmsDb]
     * and update the audio meter StateFlow at ~60 fps. Started via [ensureAudioEngineStarted]
     * as soon as preview begins (not just recording) so the meter is usable for a sound
     * check before REC — kept running across preview↔recording transitions (recording just
     * attaches an [AudioEncoder] to drain it) and only actually stopped in [stopAll] or when
     * preview itself is torn down without a recording in progress (see
     * [detachPreviewSurface]). RECORD_AUDIO is already granted before preview starts
     * (MainActivity requests CAMERA+RECORD_AUDIO together up front), so no permission-flow
     * change is needed for this.
     */
    val nativeEngine = NativeEngineBridge()
    private var audioEngineActive = false

    // USB Audio > 有線 Headset > 内蔵 Mic priority routing (§4.2) — see AudioDeviceRouter's
    // doc for why the fallback-through-candidates loop below lives here rather than in the
    // native layer. Registered for the pipeline's whole lifetime (not just while the audio
    // engine is active) so a device plugged in before the first startPreview() is already
    // known; unregistered in stopAll().
    private val audioDeviceRouter = AudioDeviceRouter(context)
    private var activeInputDeviceId: Int = 0 // 0 == oboe::kUnspecified

    // User's manual mic override (Settings sheet) — Auto keeps the USB > 有線 > 内蔵
    // priority; the others pin one kind to the front of candidateInputDevices() without
    // dropping the fallback chain (see AudioDeviceRouter.candidateInputDevices's doc), so
    // e.g. selecting "USB Audio" with nothing plugged in still records from whatever's
    // actually connected rather than silently recording nothing.
    private var preferredInputKind: AudioDeviceRouter.InputKind = AudioDeviceRouter.InputKind.Auto

    /** Settings sheet mic picker. Re-evaluates immediately if the audio engine is already
     * running (recording or previewing); otherwise just takes effect at the next
     * [ensureAudioEngineStarted]. */
    fun setPreferredInputKind(kind: AudioDeviceRouter.InputKind) {
        if (preferredInputKind == kind) return
        preferredInputKind = kind
        dispatchAudioDeviceSetChanged()
    }

    /** Fires with a human-readable label (§4.5 "現在の入力デバイス表示") whenever the
     * actually-opened input device changes — at [ensureAudioEngineStarted] time and on
     * every hot-swap. Lets the UI show what the router *actually* landed on, not just
     * what it requested (the only way to tell a USB interface that silently failed to
     * open apart from one that's genuinely in use). */
    var onAudioInputDeviceChanged: ((String) -> Unit)? = null

    // User's Settings sheet audio-quality choice (docs/HIRES_AUDIO_DESIGN.md §2/§5) — Auto
    // is not a concept here (unlike preferredInputKind): Standard is simply this engine's
    // original, only-ever-shipped behavior. actualCaptureRateHz is the engine's real
    // current rate (may be lower than audioQuality.sampleRateHz — see
    // onAudioEngineStartedLocked's doc); AUDIO_SAMPLE_RATE_HZ (the AAC/MP4 track's fixed
    // 48kHz target) never changes regardless of either.
    private var audioQuality: AudioQuality = AudioQuality.Standard
    private var actualCaptureRateHz: Int = AUDIO_SAMPLE_RATE_HZ

    /** Fires with a human-readable format label (§5 "実確定フォーマットラベル") whenever
     * [actualCaptureRateHz] changes — at [ensureAudioEngineStarted]/[setAudioQuality] time.
     * Mirrors [onAudioInputDeviceChanged]'s "report what actually happened, not what was
     * requested" principle. */
    var onAudioFormatChanged: ((String) -> Unit)? = null

    /** Settings sheet audio-quality picker. No-op while RECORDING (mirrors
     * [selectVideoConfig]'s guard — changing the capture format mid-take is not supported,
     * same reasoning: the UI is expected to disable this control during a take rather than
     * relying on this check, but it's here too as a second line of defense). If the audio
     * engine isn't running yet, just takes effect at the next [ensureAudioEngineStarted].
     * If it IS running (preview or, mid-recording somehow, guarded above), restarts it at
     * the new rate — see [restartAudioEngineForQualityChange]'s doc for why that's a full
     * engine restart rather than the lighter device-reopen [setPreferredInputKind] uses. */
    fun setAudioQuality(quality: AudioQuality) {
        if (audioQuality == quality) return
        if (pipelineState == PipelineState.RECORDING) return
        audioQuality = quality
        if (audioEngineActive) {
            pipelineScope.launch { sessionMutex.withLock { restartAudioEngineForQualityChange() } }
        }
    }

    /**
     * Full audio-engine stop+start at [audioQuality]'s new rate (docs/HIRES_AUDIO_DESIGN.md
     * §5) — heavier than [setPreferredInputKind]'s device reopen: a rate change means the
     * ring buffer's producer format changes, so unlike a device hot-swap there is no
     * meaningful "keep the stream running, just point it elsewhere" path. The meter briefly
     * reads silence during the restart, which is an accepted trade-off (same doc).
     * Native's own DSP objects preserve the user's HighPass/EQ settings across this restart
     * (see OboeFullDuplexEngine::start's setSampleRate() calls) — nothing here needs to
     * reapply them.
     */
    private suspend fun restartAudioEngineForQualityChange() {
        if (!audioEngineActive) return
        // nativeEngine.stop()/start() are both blocking native calls — offloaded per the
        // same ANR-avoidance reasoning as onAudioDeviceSetChangedLocked's doc (this runs on
        // pipelineScope's Main.immediate dispatcher). Only the native calls themselves run
        // inside withContext; plain (non-@Volatile) fields are mutated after it returns,
        // back on main — same invariant-preserving pattern as
        // stopRecordingInternalLockedAsync's doc (see attemptAudioEngineStart's doc for why
        // ensureAudioEngineStarted's logic is split in two specifically to make this
        // possible without duplicating the device-fallback loop here).
        // nativeEngine.stop() (below) closes the monitor output stream along with the
        // input stream (OboeFullDuplexEngine::stop()) — same monitoring-off bookkeeping
        // stopAudioEngineIfActive() does, replicated here since this path calls
        // nativeEngine.stop() directly rather than through that function (see this
        // method's own doc for why).
        val wasMonitoring = monitoringActive
        val requestedRateHz = audioQuality.sampleRateHz
        val outcome = withContext(Dispatchers.IO) {
            nativeEngine.stop()
            attemptAudioEngineStart(requestedRateHz)
        }
        if (wasMonitoring) {
            monitoringActive = false
            notifyMonitoringChanged(false)
        }
        if (outcome.error != null) {
            audioEngineActive = false
            activeInputDeviceId = 0
            actualCaptureRateHz = AUDIO_SAMPLE_RATE_HZ
            Log.e(TAG, "Audio engine failed to restart for quality change to $audioQuality: ${outcome.error}")
            return
        }
        applyAudioEngineStartOutcomeLocked(outcome)
    }

    // Whether live monitoring is actually running right now — the single source of truth
    // [onMonitoringEnabledChanged] reports from, since a toggle-on request can be rejected
    // (see setMonitoringEnabled's doc) or auto-reverted on hot-swap (onAudioDeviceSetChangedLocked).
    private var monitoringActive = false

    /** Fires whenever monitoring's actual on/off state changes — at [setMonitoringEnabled]
     * time (including a rejected enable request, so the UI toggle snaps back) and whenever
     * a headphone-type output is unplugged mid-monitoring. The UI should treat this as the
     * source of truth for the monitoring switch rather than echoing its own toggle. */
    var onMonitoringEnabledChanged: ((Boolean) -> Unit)? = null

    init {
        audioDeviceRouter.register { dispatchAudioDeviceSetChanged() }
    }

    /**
     * Entry point for both [AudioDeviceRouter.register]'s hot-swap callback (delivered on
     * its own background `HandlerThread`) and [setPreferredInputKind] (called directly from
     * the Settings-sheet mic picker on the main thread) — two different threads that used to
     * call [onAudioDeviceSetChangedLocked]'s predecessor directly, racing the plain `var`
     * fields it mutates (`audioEngineActive`, `activeInputDeviceId`, `monitoringActive`)
     * with each other and with [ensureAudioEngineStarted]/[stopAudioEngineIfActive], which
     * are only ever called from inside [sessionMutex]. Routing both callers through
     * [pipelineScope] (`Main.immediate`, same reasoning as [onEncoderError]'s doc) plus
     * [sessionMutex] restores the single-thread-confinement invariant those fields rely on
     * elsewhere in this class, and serializes hot-swap handling against
     * [startPreview]/[startRecording]/[stopAll] instead of interleaving with them.
     */
    private fun dispatchAudioDeviceSetChanged() {
        pipelineScope.launch { sessionMutex.withLock { onAudioDeviceSetChangedLocked() } }
    }

    /** Idempotent: safe to call even if already running (e.g. recording starting from an
     * already-active preview). Tries each candidate device in priority order, falling
     * back to the next on failure, then finally to "let the OS choose" — see
     * AudioDeviceRouter's doc for why this loop (not the native layer) is what prevents a
     * USB interface that fails to open from regressing a previously-working built-in mic.
     *
     * Requests [audioQuality]'s sample rate on every attempt (docs/HIRES_AUDIO_DESIGN.md
     * §3) — [NativeEngineBridge.start]'s own fallback ladder inside each of these attempts
     * means a hi-res request never *itself* causes this loop to fail; [actualCaptureRateHz]
     * is read back afterward and may be lower than requested. */
    private fun ensureAudioEngineStarted(): String? {
        if (audioEngineActive) return null
        val outcome = attemptAudioEngineStart(audioQuality.sampleRateHz)
        applyAudioEngineStartOutcomeLocked(outcome)
        return outcome.error
    }

    /** The blocking (native-call-only, no field mutation) half of starting the audio
     * engine — see [restartAudioEngineForQualityChange]'s doc for why this is split out
     * from [ensureAudioEngineStarted] rather than inlined there: it lets that caller run
     * the blocking part on `Dispatchers.IO` while still only mutating this class's plain
     * `var` fields back on the caller's own dispatcher, same invariant-preserving pattern
     * as [stopRecordingInternalLockedAsync]'s doc. Tries each candidate device in priority
     * order, falling back to "let the OS choose" — see AudioDeviceRouter's doc for why this
     * loop (not the native layer) is what prevents a USB interface that fails to open from
     * regressing a previously-working built-in mic. */
    private fun attemptAudioEngineStart(requestedSampleRateHz: Int): AudioEngineStartOutcome {
        for (device in audioDeviceRouter.candidateInputDevices(preferredInputKind)) {
            val error = nativeEngine.start(device.id, requestedSampleRateHz)
            if (error == null) return AudioEngineStartOutcome(device, null)
            Log.w(TAG, "Audio engine failed to start on ${audioDeviceRouter.labelFor(device)} (id=${device.id}): $error")
        }
        val fallbackError = nativeEngine.start(requestedSampleRateHz = requestedSampleRateHz)
        return AudioEngineStartOutcome(null, fallbackError)
    }

    private data class AudioEngineStartOutcome(val device: android.media.AudioDeviceInfo?, val error: String?)

    /** Field-mutation half of [attemptAudioEngineStart] — caller's responsibility to run
     * this back on its own confinement dispatcher (see that method's doc). No-op (besides
     * being safe to call) if [outcome] carries an error: [audioEngineActive] stays however
     * it already was. */
    private fun applyAudioEngineStartOutcomeLocked(outcome: AudioEngineStartOutcome) {
        if (outcome.error != null) return
        audioEngineActive = true
        activeInputDeviceId = outcome.device?.id ?: 0
        onAudioEngineStartedLocked()
        notifyInputDeviceChanged(outcome.device)
    }

    /** Reads back the engine's actually-granted rate (docs/HIRES_AUDIO_DESIGN.md §3's
     * "黙って偽装しない" principle) right after a successful [NativeEngineBridge.start] and
     * notifies the UI. Must run before any [AudioEncoder]/[HiResAudioSink] is constructed
     * for this session, since they read [actualCaptureRateHz] at construction time. */
    private fun onAudioEngineStartedLocked() {
        actualCaptureRateHz = nativeEngine.actualSampleRateHz().takeIf { it > 0 } ?: AUDIO_SAMPLE_RATE_HZ
        notifyAudioFormatChanged()
    }

    private fun stopAudioEngineIfActive() {
        if (audioEngineActive) {
            nativeEngine.stop()
            audioEngineActive = false
            activeInputDeviceId = 0
            actualCaptureRateHz = AUDIO_SAMPLE_RATE_HZ
            if (monitoringActive) {
                monitoringActive = false
                notifyMonitoringChanged(false)
            }
        }
    }

    private fun notifyInputDeviceChanged(device: android.media.AudioDeviceInfo?) {
        val label = audioDeviceRouter.labelFor(device)
        Handler(Looper.getMainLooper()).post { onAudioInputDeviceChanged?.invoke(label) }
    }

    /** §5 "実確定フォーマットラベル" (docs/HIRES_AUDIO_DESIGN.md) — human-readable summary
     * of what [actualCaptureRateHz] actually is right now, for the AUDIO panel. */
    private fun notifyAudioFormatChanged() {
        val label = when {
            actualCaptureRateHz != AUDIO_SAMPLE_RATE_HZ -> "${actualCaptureRateHz / 1000}kHz/32bit Float"
            audioQuality != AudioQuality.Standard -> "48kHz (ハイレゾ非対応デバイス)"
            else -> "48kHz"
        }
        Handler(Looper.getMainLooper()).post { onAudioFormatChanged?.invoke(label) }
    }

    /**
     * §4.2 device hot-swap + [setPreferredInputKind]: fires whenever AudioManager reports
     * *any* device added/removed — input (e.g. a USB interface plugged/unplugged
     * mid-recording) or output (e.g. monitoring headphones unplugged) — or the user
     * changes the mic picker in Settings. Handles two independent concerns off that one
     * signal, since [AudioDeviceRouter.register] only allows a single callback:
     *
     * 1. **Input routing**: no-op if the audio engine isn't running yet, or if the
     *    highest-priority candidate hasn't actually changed (e.g. a *lower*-priority
     *    device was plugged in while USB is already active). Falls back through the
     *    remaining candidates on failure, same reasoning as [ensureAudioEngineStarted].
     *
     *    **実機未検証**: [insertSilence]'s frame count only approximates the true capture
     *    gap with the wall-clock time this method itself takes to reopen the stream — no
     *    hardware is available in this dev environment to verify that approximation
     *    against a real device unplug. It keeps Audio PTS's cumulative-sample-count basis
     *    (§4.3) monotonically advancing through the switch rather than jumping backward
     *    relative to Video PTS, which is the property that actually matters; exact
     *    silence-duration accuracy is a secondary concern for what is meant to be a rare,
     *    brief event.
     *
     * 2. **Monitoring safety**: if monitoring is currently on and the headphone-type
     *    output that made it safe to enable (see [setMonitoringEnabled]'s doc) just
     *    disappeared, force it off rather than letting it silently fall back to the
     *    built-in speaker and feed back into the mic.
     *
     * Caller must already hold [sessionMutex] (see [dispatchAudioDeviceSetChanged]). The
     * blocking native calls run inside [withContext]\(Dispatchers.IO\) — same reasoning as
     * [stopRecordingInternalLockedAsync]'s doc: this used to run every native call
     * (including [NativeEngineBridge.reopenInputStream], which was already known to be slow
     * enough to ANR the main thread once before — see [AudioDeviceRouter.register]'s doc) synchronously on
     * whatever thread called it, which for [setPreferredInputKind] is main.
     */
    private suspend fun onAudioDeviceSetChangedLocked() {
        if (monitoringActive && !audioDeviceRouter.hasSafeMonitoringOutput()) {
            Log.w(TAG, "Monitoring output device disconnected; disabling monitoring to avoid mic feedback")
            withContext(Dispatchers.IO) { nativeEngine.setMonitoringEnabled(false) }
            monitoringActive = false
            notifyMonitoringChanged(false)
        }

        if (!audioEngineActive) return
        val candidates = audioDeviceRouter.candidateInputDevices(preferredInputKind)
        val preferred = candidates.firstOrNull() ?: return
        if (preferred.id == activeInputDeviceId) return

        Log.i(TAG, "Input device set changed, switching to ${audioDeviceRouter.labelFor(preferred)} (id=${preferred.id})")
        val gapStartNanos = System.nanoTime()
        var opened: android.media.AudioDeviceInfo? = null
        withContext(Dispatchers.IO) {
            for (device in candidates) {
                val error = nativeEngine.reopenInputStream(device.id)
                if (error == null) {
                    opened = device
                    break
                }
                Log.w(TAG, "reopenInputStream failed on ${audioDeviceRouter.labelFor(device)} (id=${device.id}): $error")
            }
        }
        val openedDevice = opened
        if (openedDevice == null) {
            Log.e(TAG, "All candidate input devices failed to reopen after a device change; keeping the previous stream closed")
            return
        }

        val gapNanos = System.nanoTime() - gapStartNanos
        // Engine-rate frames (docs/HIRES_AUDIO_DESIGN.md §4/§6.5): insertSilence() pushes
        // directly into the native ring buffer, which is always in actualCaptureRateHz
        // frames regardless of the AAC track's fixed AUDIO_SAMPLE_RATE_HZ target.
        val gapFrames = (gapNanos * actualCaptureRateHz / 1_000_000_000L).toInt()
        withContext(Dispatchers.IO) { nativeEngine.insertSilence(gapFrames) }
        activeInputDeviceId = openedDevice.id
        notifyInputDeviceChanged(openedDevice)
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // State preserved across recording cycles
    // ──────────────────────────────────────────────────────────────────────────────

    private var selectedLens: CameraCapabilityInspector.LensInfo? = null
    private var capabilities: CameraCapabilities? = null
    private var requestFactory: ManualCaptureRequestFactory? = null
    private var currentParams = CameraParams()
    private var previewSurface: Surface? = null
    private var histogramReader: com.aucampro.recorder.camera.LuminanceHistogramReader? = null

    /** See [com.aucampro.recorder.camera.LuminanceHistogramReader]'s doc — preview-only,
     * frozen (not updated) while a recording is in progress. */
    var onHistogramUpdated: ((FloatArray) -> Unit)? = null

    // Still-photo capture (§Photo mode). Preview-session-only, same reasoning as
    // histogramReader — see startPreview()'s call site and capturePhoto()'s doc.
    private var photoReader: android.media.ImageReader? = null
    // photoReader's onImageAvailable listener runs savePhotoToMediaStore() — a multi-MB
    // JPEG buffer read plus a MediaStore/disk write. That listener used to run on
    // Handler(Looper.getMainLooper()), which is a real ANR/frame-drop risk; it now runs
    // on this dedicated background thread instead. Owned 1:1 with photoReader — every
    // place that closes photoReader must also quitSafely() + null this, or the old
    // thread leaks until the next startPreview() call happens to clean it up.
    private var photoHandlerThread: android.os.HandlerThread? = null

    // User-configurable settings
    private var nextVideoConfig: CameraCapabilityInspector.VideoConfigCandidate? = null
    private var storageLocation: StorageLocation = StorageLocation.AppPrivate
    private var segmentDurationMinutes: Int = 5

    private enum class PipelineState { IDLE, PREVIEWING, RECORDING }
    private var pipelineState = PipelineState.IDLE

    var onAutoWbGainsMeasured: ((android.hardware.camera2.params.RggbChannelVector, Double) -> Unit)? = null
    var onAutoFocusMeasured: ((Float) -> Unit)? = null

    // §ギャラリー連携: fires with the MediaStore URI of a newly saved photo, or the last
    // segment of a newly finished recording — MainScreen's GalleryThumbnailButton uses
    // this to show/open the most recent capture. Video only fires when [storageLocation]
    // is [StorageLocation.PublicMovies] (a MediaStore URI) — AppPrivate video files stay
    // as plain app-private Files with no MediaStore entry to point a thumbnail/viewer
    // Intent at, and wiring that up would need a FileProvider this app doesn't have yet;
    // left as a known gap rather than half-built, since PublicMovies is now the default.
    var onMediaCaptured: ((uri: android.net.Uri, isVideo: Boolean) -> Unit)? = null

    // Tap/long-press-to-focus (§4.1) — see [com.aucampro.recorder.camera.FocusController]'s
    // doc. (Re)created per [startPreview] since it's bound to that session's
    // CameraCharacteristics/ManualCaptureRequestFactory; torn down alongside the session in
    // [stopPreviewSession]/[stopAll].
    private var focusController: com.aucampro.recorder.camera.FocusController? = null
    var onTapToFocusLocked: ((focusDistanceDiopters: Float) -> Unit)? = null

    // §フォーカス位置表示 — see FocusController.onFocusIndicatorChanged's doc. Fires once
    // per state transition (Scanning at tap time, then Locked/Failed once the scan
    // resolves) so the UI can draw/animate a focus reticle.
    var onFocusIndicatorChanged: ((
        normalizedX: Float,
        normalizedY: Float,
        state: com.aucampro.recorder.camera.FocusController.FocusIndicatorState,
    ) -> Unit)? = null

    // ──────────────────────────────────────────────────────────────────────────────
    // Encoder / muxer (only alive during RECORDING)
    // ──────────────────────────────────────────────────────────────────────────────

    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var muxerController: SegmentedMuxerController? = null
    private var ptsClockDomain: PtsClockDomain? = null
    private var currentOutputDir: File? = null

    // Hi-res WAV サイドカー (docs/HIRES_AUDIO_DESIGN.md) — null unless this take actually
    // landed on a hi-res capture rate (see startRecording's construction site). Held here
    // (not just inside AudioEncoder) so emergencyFinalizeCurrentSegment() can reach it,
    // mirroring muxerController's own reason for being a top-level field.
    private var hiResAudioSink: HiResAudioSink? = null

    // ──────────────────────────────────────────────────────────────────────────────
    // Public methods
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the camera and streams to [surface]. If [surface] is null the camera is opened
     * only for capability inspection (used during the first-run permission flow before the
     * SurfaceView is ready).
     *
     * Returns the [CameraCapabilities] snapshot so the ViewModel can populate slider ranges.
     * On failure returns null and logs the error; the caller should handle gracefully.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun startPreview(
        surface: Surface?,
        params: CameraParams = currentParams,
        targetCameraId: String? = null,
    ): CameraCapabilities? = sessionMutex.withLock {
        if (pipelineState == PipelineState.RECORDING) {
            // Preview surface (re)attach while a recording is already in progress — e.g.
            // the app returned to the foreground after [detachPreviewSurface] dropped to
            // an encoder-only session. Fold the surface back into the live session; the
            // encoder-only session keeps recording running either way, so a failure here
            // only means the viewfinder doesn't come back, not that recording stops (see
            // reconfigureRecordingSurfacesLocked's doc).
            previewSurface = surface
            if (surface != null) reconfigureRecordingSurfacesLocked()
            return@withLock capabilities
        }
        // Stop any existing preview session before re-opening (surface may have changed).
        if (pipelineState == PipelineState.PREVIEWING) stopPreviewSession()

        try {
            // [targetCameraId] lets a caller (CameraControlViewModel.switchLens) open a
            // *specific* rear lens instead of always falling back to the standard one.
            // Before this parameter existed, switchLens() had no way to communicate which
            // lens it wanted — startPreview() would silently reopen findStandardRearLens()
            // every time regardless of which AvailableLens the user tapped, so the lens
            // selector UI updated its own highlighted state but the actual camera session
            // never changed (real-device finding: lens buttons appeared to do nothing).
            val lens = if (targetCameraId != null) {
                CameraCapabilityInspector.LensInfo(
                    cameraId = targetCameraId,
                    focalLengthMm = 0f,
                    isStandardRearLens = false,
                )
            } else {
                capabilityInspector.findStandardRearLens()
                    ?: error("No standard rear lens found on this device")
            }

            // Default candidate for the CameraCapabilities snapshot (used to populate slider
            // ranges etc.). The user's actual Settings selection (selectVideoConfig()) is
            // consulted later, at encoder-creation time in startRecording() — preview doesn't
            // care about the eventual encoder's resolution, so there is no need to restart
            // the preview session just because the user picked a different video config.
            // §1.2's primary/fallback pair (4K HEVC / 1080p60 H.264) covers the capable real
            // devices this app targets. If neither is actually encodable (e.g. a software
            // encoder capped below 60fps, as on the emulator's swiftshader codec), fall back
            // to the broader candidate list built for the Settings resolution picker rather
            // than failing outright — better to record at a lower spec than not at all.
            val videoConfig = capabilityInspector.videoConfigCandidates().firstOrNull {
                capabilityInspector.isVideoConfigSupported(
                    it.mimeType, it.width, it.height, it.frameRate, it.bitrate,
                )
            } ?: capabilityInspector.supportedVideoConfigs(lens.cameraId).firstOrNull()
                ?: error("No supported video config (checked HEVC 4K30, H.264 1080p60, and the broader fallback list)")

            val characteristics = sessionController.characteristicsFor(lens.cameraId)
            val clamper = CaptureRangeClamper.fromCharacteristics(characteristics)

            val caps = CameraCapabilities(
                isoRange = clamper.sensitivityRange,
                exposureTimeRangeNanos = clamper.exposureTimeRangeNanos,
                minFocusDistanceDiopters = clamper.minFocusDistanceDiopters,
                supportsManualWb = capabilityInspector.supportsManualWhiteBalance(lens.cameraId),
                hardwareLevel = capabilityInspector.hardwareLevel(lens.cameraId),
                videoConfig = videoConfig,
                cameraId = lens.cameraId,
                apertureFNumber = characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES,
                )?.minOrNull(),
            )

            selectedLens = lens
            capabilities = caps
            requestFactory = ManualCaptureRequestFactory(characteristics)
            currentParams = params
            previewSurface = surface

            focusController = com.aucampro.recorder.camera.FocusController(
                characteristics = characteristics,
                captureRequestFactory = requireNotNull(requestFactory),
                requestSubmitter = com.aucampro.recorder.camera.FocusController.RequestSubmitter { configure ->
                    sessionController.submitSingleRequest(configure)
                },
                onFocusLocked = { distance ->
                    Handler(Looper.getMainLooper()).post { onTapToFocusLocked?.invoke(distance) }
                },
                onFocusIndicatorChanged = { x, y, indicatorState ->
                    Handler(Looper.getMainLooper()).post { onFocusIndicatorChanged?.invoke(x, y, indicatorState) }
                },
            )

            var frameCount = 0
            sessionController.captureResultListener = CameraSessionController.CaptureResultListener { result ->
                // Every frame, not throttled like the WB/AF passive-measurement block below
                // — a tap-to-focus scan needs to see CONTROL_AF_STATE transitions promptly
                // to feel responsive (no-op when no scan is in progress; see this method's
                // own doc for why it's cheap to call unconditionally).
                focusController?.onCaptureResult(result, System.nanoTime())

                frameCount++
                if (frameCount % 10 == 0) {
                    if (currentParams?.wbAuto == true) {
                        val gains = result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)
                        if (gains != null) {
                            val k = ColorTemperatureConverter.rggbGainsToKelvin(gains)
                            Handler(Looper.getMainLooper()).post {
                                onAutoWbGainsMeasured?.invoke(gains, k)
                            }
                        } else {
                            Log.w("RecordingPipeline", "WB is AUTO but COLOR_CORRECTION_GAINS is null")
                        }
                    }
                    if (currentParams?.afAuto == true) {
                        val focus = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
                        if (focus != null) {
                            Handler(Looper.getMainLooper()).post {
                                onAutoFocusMeasured?.invoke(focus)
                            }
                        } else {
                            Log.w("RecordingPipeline", "AF is AUTO but LENS_FOCUS_DISTANCE is null")
                        }
                    }
                }
            }

            // Histogram sampler (Sony__________.pdf's "ヒストグラム(輝度分布グラフ)" UI
            // assist) — preview-only best-effort extra stream, see
            // LuminanceHistogramReader's doc for why it's deliberately tiny/throttled and
            // never added to the recording session. A fresh reader is created per
            // startPreview() call (lens may have changed, and YUV sizes are per-camera).
            histogramReader?.close()
            histogramReader = com.aucampro.recorder.camera.LuminanceHistogramReader.smallestYuvSize(characteristics)
                ?.let { size ->
                    try {
                        com.aucampro.recorder.camera.LuminanceHistogramReader(size.width, size.height) { bins ->
                            Handler(Looper.getMainLooper()).post { onHistogramUpdated?.invoke(bins) }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Histogram reader creation failed, continuing without it", e)
                        null
                    }
                }

            // Still-photo capture reader (§Photo mode) — also preview-session-only (never
            // added to the recording session's surface set), same reasoning as the
            // histogram reader. Sized to the exact 3840x2160 the recording path already
            // proves out (see supportedVideoConfigs()'s 3840x2880 doc for why picking an
            // unproven size here is a real risk on this hardware), falling back to the
            // largest available JPEG size only if that exact one isn't offered.
            photoReader?.close()
            photoHandlerThread?.quitSafely()
            photoReader = pickPhotoOutputSize(characteristics)?.let { size ->
                try {
                    val ht = android.os.HandlerThread("PhotoCapture").apply { start() }
                    photoHandlerThread = ht
                    android.media.ImageReader.newInstance(
                        size.width, size.height, android.graphics.ImageFormat.JPEG, 2,
                    ).apply {
                        setOnImageAvailableListener({ r ->
                            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                            try {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                savePhotoToMediaStore(bytes)
                            } finally {
                                image.close()
                            }
                        }, Handler(ht.looper))
                    }
                } catch (e: Exception) {
                    photoHandlerThread?.quitSafely()
                    photoHandlerThread = null
                    Log.w(TAG, "Photo reader creation failed, continuing without it", e)
                    null
                }
            }

            // If no surface is available yet, defer actually opening the session.
            if (surface != null) {
                val extraSurfaces = listOfNotNull(histogramReader?.surface, photoReader?.surface)
                try {
                    sessionController.startRepeating(
                        cameraId = lens.cameraId,
                        outputSurfaces = listOf(surface) + extraSurfaces,
                        // Histogram wants every preview frame (see its own doc) but the
                        // photo reader must NOT be targeted by the repeating request — see
                        // startRepeating()'s repeatingTargets doc for the real-device bug
                        // this fixes (continuous full-res JPEG encoding of every frame).
                        repeatingTargets = listOfNotNull(surface, histogramReader?.surface),
                        requestFactory = requireNotNull(requestFactory),
                        params = params,
                    )
                } catch (e: Exception) {
                    if (extraSurfaces.isNotEmpty()) {
                        // The full combo (preview + histogram + photo) isn't guaranteed
                        // supported on every device — real-device testing already found
                        // this exact camera rejects some concurrent stream combos outright
                        // (see supportedVideoConfigs()'s 3840x2880 doc). Retry preview-only
                        // rather than losing the whole preview over UI-assist/photo extras.
                        Log.w(TAG, "Preview session with extra streams failed, retrying preview-only", e)
                        histogramReader?.close(); histogramReader = null
                        photoReader?.close(); photoReader = null
                        photoHandlerThread?.quitSafely(); photoHandlerThread = null
                        sessionController.startRepeating(
                            cameraId = lens.cameraId,
                            outputSurfaces = listOf(surface),
                            requestFactory = requireNotNull(requestFactory),
                            params = params,
                        )
                    } else {
                        throw e
                    }
                }
                pipelineState = PipelineState.PREVIEWING

                // Best-effort: a broken/busy mic shouldn't fail the whole preview (the
                // camera side is still useful on its own) — the meter will just read
                // silence-floor if this fails, same as it would look before this method
                // existed. startRecording() calls the same idempotent helper and DOES
                // surface a failure, since recording without audio is a harder failure.
                val audioError = ensureAudioEngineStarted()
                if (audioError != null) Log.w(TAG, "Audio engine failed to start during preview: $audioError")
            }

            Log.i(TAG, "Preview started: cameraId=${lens.cameraId} videoConfig=$videoConfig")
            caps
        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed", e)
            null
        }
    }

    /**
     * Starts the full encode/mux pipeline. Must be in PREVIEWING state.
     *
     * Session is reconfigured to add the encoder's InputSurface alongside [previewSurface],
     * introducing a brief (~100-200ms) camera freeze during session recreation — acceptable
     * for a recording-start event that the user has explicitly triggered.
     *
     * On success emits [Event.Started]. On any failure emits [Event.Failed] and cleans up.
     */
    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    suspend fun startRecording(onEvent: (Event) -> Unit) {
        if (pipelineState != PipelineState.PREVIEWING) {
            onEvent(Event.Failed("Pipeline is not in PREVIEWING state (current: $pipelineState)"))
            return
        }

        val lens = selectedLens ?: run {
            onEvent(Event.Failed("No lens selected — call startPreview() first"))
            return
        }
        val factory = requestFactory ?: run {
            onEvent(Event.Failed("No request factory — call startPreview() first"))
            return
        }
        val caps = capabilities ?: run {
            onEvent(Event.Failed("Capabilities not yet resolved"))
            return
        }

        try {
            recordingErrorHandled.set(false)

            val pts = PtsClockDomain()
            ptsClockDomain = pts
            pts.start()

            // Prefer the user's Settings selection (selectVideoConfig()) when one is set and
            // still valid for this device; otherwise fall back to the default picked at
            // startPreview() time. Re-validated here (not just trusted from the UI's cached
            // selection) since the lens may have changed since the user picked it.
            val recordingVideoConfig = nextVideoConfig?.takeIf {
                capabilityInspector.isVideoConfigSupported(it.mimeType, it.width, it.height, it.frameRate, it.bitrate)
            } ?: caps.videoConfig

            val takeTimestampMs = System.currentTimeMillis()
            val outputDir = File(
                context.getExternalFilesDir(null),
                "recordings/$takeTimestampMs",
            )
            outputDir.mkdirs()
            currentOutputDir = outputDir

            // Sampled once, here, at the start of the take — see DeviceOrientationTracker's
            // doc for why this is needed at all despite the app window being locked to
            // sensorLandscape (portrait-held recording support).
            val orientationHint = orientationTracker.orientationHintDegreesFor(
                sessionController.characteristicsFor(caps.cameraId),
            )
            val muxer = SegmentedMuxerController(
                outputPathForSegment = { index ->
                    File(outputDir, "${APP_NAME_TAG}_${takeTimestampMs}_segment_$index.mp4").absolutePath
                },
                segmentDurationUs = segmentDurationMinutes * 60 * 1_000_000L,
                orientationHintDegrees = orientationHint,
            )
            muxerController = muxer

            val video = VideoEncoder(
                mimeType = recordingVideoConfig.mimeType,
                width = recordingVideoConfig.width,
                height = recordingVideoConfig.height,
                frameRate = recordingVideoConfig.frameRate,
                bitrate = recordingVideoConfig.bitrate,
                ptsClockDomain = pts,
                callback = object : VideoEncoder.Callback {
                    override fun onOutputFormatChanged(format: MediaFormat) =
                        muxer.onVideoFormatChanged(format)

                    override fun onEncodedFrame(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) =
                        muxer.onVideoSample(buffer, bufferInfo)

                    override fun onError(exception: Exception) =
                        onEncoderError("VideoEncoder", exception, onEvent)
                },
            )
            videoEncoder = video

            val engineError = ensureAudioEngineStarted()
            if (engineError != null) error("Audio engine failed to start: $engineError")

            // Hi-res WAV サイドカー (docs/HIRES_AUDIO_DESIGN.md §1/§6.2/§6.4) — only when
            // the engine actually landed on a rate above the AAC track's fixed target (a
            // hi-res *request* that fell back to 48kHz gets no sidecar, matching
            // notifyAudioFormatChanged's "48kHz (ハイレゾ非対応デバイス)" label: there is
            // nothing lossless-and-higher-rate to capture in that case). Segmented the same
            // way as the MP4 (same take timestamp, same segmentDurationMinutes).
            val hiResSink = if (actualCaptureRateHz > AUDIO_SAMPLE_RATE_HZ) {
                HiResAudioSink(
                    outputPathForSegment = { index ->
                        File(outputDir, "${APP_NAME_TAG}_${takeTimestampMs}_segment_$index.wav").absolutePath
                    },
                    sampleRateHz = actualCaptureRateHz,
                    channelCount = AUDIO_CHANNEL_COUNT,
                    segmentDurationUs = segmentDurationMinutes * 60 * 1_000_000L,
                )
            } else {
                null
            }
            hiResAudioSink = hiResSink

            val audio = AudioEncoder(
                sampleRateHz = AUDIO_SAMPLE_RATE_HZ,
                channelCount = AUDIO_CHANNEL_COUNT,
                bitrate = AUDIO_BITRATE_BPS,
                nativeEngine = nativeEngine,
                ptsClockDomain = pts,
                callback = object : AudioEncoder.Callback {
                    override fun onOutputFormatChanged(format: MediaFormat) =
                        muxer.onAudioFormatChanged(format)

                    override fun onEncodedFrame(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) =
                        muxer.onAudioSample(buffer, bufferInfo)

                    override fun onError(exception: Exception) =
                        onEncoderError("AudioEncoder", exception, onEvent)
                },
                captureSampleRateHz = actualCaptureRateHz,
                hiResSink = hiResSink,
            )
            audioEncoder = audio

            video.start()
            // AudioEncoder.start() seeds PtsClockDomain's audio anchor itself (retrying
            // NativeEngineBridge.getInputTimestamp() for frame-correlation accuracy — see
            // its doc and docs/ARCHITECTURE.md §Phase3). nativeEngine.start() must complete
            // before this call.
            audio.start()

            // Reconfigure the session to include both the preview and the encoder's InputSurface.
            // Camera2 requires closing the current session and opening a new one when the
            // output surface set changes — this causes a brief preview freeze (~100-200ms)
            // at recording-start time, which is an acceptable UX trade-off for v1.
            sessionMutex.withLock {
                sessionController.stop()
                sessionController.startRepeating(
                    cameraId = lens.cameraId,
                    outputSurfaces = listOfNotNull(previewSurface, video.inputSurface),
                    requestFactory = factory,
                    params = currentParams,
                )
            }

            pipelineState = PipelineState.RECORDING
            Log.i(TAG, "Recording started → $outputDir")
            onEvent(Event.Started(outputDir))
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            sessionMutex.withLock { stopRecordingInternalLocked(restartPreview = false) }
            onEvent(Event.Failed(e.message ?: e.toString(), sessionStopped = true))
        }
    }

    /**
     * Stops recording: drains all encoders, finalises muxers, then automatically restarts
     * the preview-only session so the viewfinder stays live. Must be called from a
     * coroutine; `awaitEndOfStream()` blocks until the VideoEncoder is fully drained.
     *
     * The stop order follows docs/ARCHITECTURE.md §Phase4's stop-sequence rationale
     * (camera session stops, THEN encoders are drained — keeping the Audio tail ≤ 0.75s
     * per the real-device measurement). The audio *engine* itself (the mic) is no longer
     * part of this stop sequence — see [ensureAudioEngineStarted]'s doc for why it now
     * outlives individual recordings, for continuous metering.
     */
    suspend fun stopRecording() {
        if (pipelineState != PipelineState.RECORDING) return
        sessionMutex.withLock { stopRecordingInternalLockedAsync() }
    }

    /**
     * Called when the Activity's preview Surface becomes unavailable (backgrounded /
     * screen locked / `SurfaceView` destroyed).
     *
     * If a recording is in progress, reconfigures the live capture session to run on the
     * encoder's InputSurface alone so recording keeps going without a preview consumer —
     * this (together with the foreground service holding `FOREGROUND_SERVICE_CAMERA` so
     * the OS still permits camera access while backgrounded) is what lets a recording
     * survive screen-off per §4.6. If not recording, this just tears down the
     * preview-only session as before.
     *
     * **実機未検証**: このAVDのカメラHALはこのアプリのVideoEncoder
     * InputSurfaceへのストリーミングを(プレビューSurfaceの有無に関わらず)常に
     * 拒否するため、`detachPreviewSurface()`が実際にセッションをエンコーダ単体へ
     * 再構成できるかはエミュレータ上で検証できていない(§下記の実験記録参照)。
     * ロジック自体は標準的なCamera2の単一Surfaceストリーミングであり実機では
     * 動作するはずだが、実機での確認が必要。
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun detachPreviewSurface() = sessionMutex.withLock {
        val wasRecording = pipelineState == PipelineState.RECORDING
        previewSurface = null
        if (wasRecording) {
            reconfigureRecordingSurfacesLocked()
        } else {
            stopPreviewSession()
            // Only here: leaving preview with no recording in progress is the one case
            // where nothing still needs the mic (see ensureAudioEngineStarted's doc) — stop
            // it so it doesn't stay hot while backgrounded.
            stopAudioEngineIfActive()
        }
    }

    /**
     * Stops everything (preview + recording if active). Call from `ViewModel.onCleared()`.
     */
    fun stopAll() {
        // Non-suspend (called synchronously from ViewModel.onCleared(), where
        // viewModelScope is already being torn down — launching a coroutine to properly
        // await sessionMutex there is unreliable). tryLock() instead: normally acquires
        // immediately since nothing else should be mid-reconfiguration at teardown time;
        // if something IS holding it (e.g. a screen-lock reconfiguration racing the
        // ViewModel's own destruction), proceed anyway and log — this is unconditional
        // teardown, so blocking indefinitely here would be worse than a logged race.
        val gotLock = sessionMutex.tryLock()
        if (!gotLock) Log.w(TAG, "stopAll(): sessionMutex busy, proceeding without it")
        try {
            if (pipelineState == PipelineState.RECORDING) {
                stopRecordingInternalLocked(restartPreview = false)
            }
            stopPreviewSession()
            sessionController.release()
        } finally {
            if (gotLock) sessionMutex.unlock()
        }
        nativeEngine.close()
        audioDeviceRouter.unregister()
        orientationTracker.stop()
        histogramReader?.close()
        histogramReader = null
        photoReader?.close()
        photoReader = null
        photoHandlerThread?.quitSafely()
        photoHandlerThread = null
        focusController = null
        audioEngineActive = false
        pipelineState = PipelineState.IDLE
        pipelineScope.cancel()
    }

    /**
     * Sets the video config to use for the next call to [startRecording].
     * No-op while recording.
     */
    fun selectVideoConfig(config: CameraCapabilityInspector.VideoConfigCandidate) {
        if (pipelineState == PipelineState.RECORDING) return
        nextVideoConfig = config
    }

    /** Sets where recordings are saved. Takes effect on the next [startRecording]. */
    fun setStorageLocation(location: StorageLocation) {
        storageLocation = location
    }

    /** Sets how many minutes each segment file should be before a new file is opened. */
    fun setSegmentDurationMinutes(minutes: Int) {
        segmentDurationMinutes = minutes
    }

    /**
     * Applies [params] to the live repeating request without restarting the session.
     * The Camera2 HAL replaces the previous request at the next frame boundary, so
     * viewfinder changes are effectively instant (one frame latency).
     */
    fun updateCameraParams(params: CameraParams) {
        currentParams = params
        sessionController.updateCaptureParams(params)
    }

    fun setEqBand(band: Int, freqHz: Float, q: Float, gainDb: Float) {
        nativeEngine.setEqBandParams(band, freqHz, q, gainDb)
    }

    fun setInputGainDb(gainDb: Float) {
        nativeEngine.setInputGainDb(gainDb)
    }

    fun setMakeupGainDb(gainDb: Float) {
        nativeEngine.setMakeupGainDb(gainDb)
    }

    fun setHighPassEnabled(enabled: Boolean) {
        nativeEngine.setHighPassEnabled(enabled)
    }

    fun setHighPassCutoffHz(cutoffHz: Float) {
        nativeEngine.setHighPassCutoffHz(cutoffHz)
    }

    /**
     * §4.5 "モニタリング再生". Rejects an enable request (leaving monitoring off and
     * reporting that via [onMonitoringEnabledChanged]) unless a headphone-type output is
     * currently connected — see [AudioDeviceRouter.hasSafeMonitoringOutput]'s doc for why:
     * monitoring through the built-in speaker while recording from the mic would feed the
     * speaker's own output back into the mic. Already-enabled monitoring is also force-off
     * if that output later disappears — see [onAudioDeviceSetChangedLocked].
     */
    fun setMonitoringEnabled(enabled: Boolean, outputDeviceId: Int = 0) {
        if (enabled && !audioDeviceRouter.hasSafeMonitoringOutput()) {
            Log.w(TAG, "setMonitoringEnabled(true) rejected: no headphone-type output connected (would risk mic feedback)")
            notifyMonitoringChanged(false)
            return
        }
        val error = nativeEngine.setMonitoringEnabled(enabled, outputDeviceId)
        if (error != null) Log.w(TAG, "setMonitoringEnabled($enabled) returned error: $error")
        monitoringActive = enabled && error == null
        notifyMonitoringChanged(monitoringActive)
    }

    private fun notifyMonitoringChanged(enabled: Boolean) {
        Handler(Looper.getMainLooper()).post { onMonitoringEnabledChanged?.invoke(enabled) }
    }

    /**
     * Best-effort crash-safety net (§4.6): closes whatever [MediaMuxer] is currently open
     * (via [SegmentedMuxerController.stop]) so it gets a valid moov box, without trying to
     * cleanly signal EOS or drain the encoders first. Meant to be called from a
     * [Thread.UncaughtExceptionHandler] — see [com.aucampro.recorder.AuCamPROApplication]
     * — where the process is about to die and there is no time (or dispatcher guarantee)
     * for the normal suspend stop sequence.
     *
     * Because [SegmentedMuxerController] finalizes each *past* segment as soon as
     * rotation completes (see its class doc), only the current in-flight segment is ever
     * at risk of being lost to a crash — this call is what saves *that* one. A few
     * trailing frames already handed to the encoder but not yet delivered to the muxer
     * callback are accepted as lost; the goal is a playable file, not a complete one.
     *
     * Deliberately swallows all exceptions — this runs during process teardown and must
     * never throw past the caller (which re-throws to the platform's default crash
     * handler regardless of what happens here).
     *
     * **実機未検証**: 実際にKotlin/Java例外によるクラッシュを発生させての検証は
     * まだ行っていない(ネイティブクラッシュ・ANR・強制killはこの経路では
     * そもそも救えない——JVM例外ハンドラが呼ばれるケースのみ対象)。
     */
    fun emergencyFinalizeCurrentSegment() {
        try {
            muxerController?.stop()
            Log.w(TAG, "emergencyFinalizeCurrentSegment: muxer finalized")
        } catch (e: Throwable) {
            Log.e(TAG, "emergencyFinalizeCurrentSegment failed", e)
        }
        try {
            // Same best-effort acceptance as the muxer above (docs/HIRES_AUDIO_DESIGN.md
            // §6.4) — may race AudioEncoder's own drain thread mid-write() if that thread
            // is still alive when the crash handler runs; WavFileWriter.close()'s
            // back-patch is idempotent so this is safe to call regardless.
            hiResAudioSink?.close()
            Log.w(TAG, "emergencyFinalizeCurrentSegment: hi-res WAV finalized")
        } catch (e: Throwable) {
            Log.e(TAG, "emergencyFinalizeCurrentSegment (WAV) failed", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Caller must already hold [sessionMutex] (see [stopAll]). Blocking — the encoder
     * drain/muxer-finalize/MediaStore-export sequence below can take seconds, which is
     * only acceptable because [stopAll] runs at `ViewModel.onCleared()` teardown, not on
     * an input-dispatch-sensitive path. The interactive stop path uses
     * [stopRecordingInternalLockedAsync] instead — see its doc for why this one can't
     * just be reused there.
     */
    private fun stopRecordingInternalLocked(restartPreview: Boolean) {
        // Camera session stops (see stop-sequence doc above); the audio *engine* deliberately
        // does NOT stop here anymore — it keeps running for the meter across the return to
        // Previewing (see [ensureAudioEngineStarted]'s doc). Only the AudioEncoder's drain
        // (just below) stops consuming from it; the underlying ring buffer harmlessly
        // wraps/overruns in the meantime, which does not affect this recording's already
        // in-flight drain/EOS sequence (see ensureAudioEngineStarted's doc for the split).
        sessionController.stop()

        videoEncoder?.let { encoder ->
            encoder.signalEndOfStream()
            encoder.awaitEndOfStream()
            encoder.stop()
        }
        audioEncoder?.stop()  // also closes hiResAudioSink internally — see AudioEncoder's drain-thread finally block
        muxerController?.stop()

        ptsClockDomain = null
        videoEncoder = null
        audioEncoder = null
        muxerController = null
        hiResAudioSink = null
        pipelineState = PipelineState.IDLE

        // Preview restart itself is *not* done here despite the restartPreview parameter:
        // this is a plain (non-suspend) function, and re-opening the camera session is a
        // suspend call. restartPreview only distinguishes the two callers' intent —
        // stopRecording() (true) relies on the ViewModel calling startPreview() again after
        // it returns; stopAll() (false) tears the pipeline down instead. See stopRecording's
        // doc.
        currentOutputDir?.let {
            exportToPublicMoviesIfRequested(it)
            exportWavIfRequested(it)
        }
        currentOutputDir = null
    }

    /**
     * Caller must already hold [sessionMutex]. Interactive counterpart of
     * [stopRecordingInternalLocked] used only by [stopRecording] (the REC-button/hardware-key
     * path).
     *
     * **実機で発見**: [stopRecording] is `suspend` but is launched from
     * `CameraControlViewModel`'s `viewModelScope.launch {}`, which defaults to
     * `Dispatchers.Main.immediate` — so before this fix, every blocking call this sequence
     * makes (encoder drain-and-stop, muxer finalize, and especially
     * [exportToPublicMoviesIfRequested]'s synchronous MediaStore file copy) ran directly on
     * the main thread. Confirmed on-device: a single stop press blocked `InputDispatcher`
     * for 7-8 seconds — past Android's 5s ANR threshold — producing a real
     * 「ProCamera」は応答していません dialog, and queuing up any REC key presses sent while
     * blocked so they fired all at once (looking like start/stop was being ignored) once
     * the main thread finally freed up.
     *
     * [sessionController.stop] deliberately stays on the calling (main) dispatcher rather
     * than moving into the [withContext] block below: [CameraSessionController] is not
     * internally synchronized against the camera-parameter setters (`setIso`/`setZoom`/...)
     * that call [sessionController]'s methods synchronously from main — see its class doc.
     * Moving just this call off-thread would race those setters over the same `session`/
     * `device` fields. It's also not the source of the multi-second block (Camera2 session
     * teardown is fast; the encoder/muxer/export work below is what's slow), so there's no
     * benefit to moving it anyway.
     *
     * The blocking work below is captured into locals *before* entering [withContext], and
     * [pipelineState]/the encoder-muxer fields are only mutated after it returns (back on
     * main) — these fields are plain (non-`@Volatile`) `var`s relied on for single-thread
     * (main) confinement elsewhere in this class (e.g. [selectVideoConfig]'s unguarded
     * `pipelineState` read), so this keeps that invariant intact instead of trading one race
     * for another.
     */
    private suspend fun stopRecordingInternalLockedAsync() {
        sessionController.stop()

        val video = videoEncoder
        val audio = audioEncoder
        val muxer = muxerController
        val dir = currentOutputDir

        withContext(Dispatchers.IO) {
            video?.let { encoder ->
                encoder.signalEndOfStream()
                encoder.awaitEndOfStream()
                encoder.stop()
            }
            audio?.stop()  // also closes hiResAudioSink internally — see AudioEncoder's drain-thread finally block
            muxer?.stop()
            dir?.let {
                exportToPublicMoviesIfRequested(it)
                exportWavIfRequested(it)
            }
        }

        ptsClockDomain = null
        videoEncoder = null
        audioEncoder = null
        muxerController = null
        hiResAudioSink = null
        pipelineState = PipelineState.IDLE
        currentOutputDir = null
    }

    /**
     * If [storageLocation] is [StorageLocation.PublicMovies], copies each finalised segment
     * `.mp4` out of the app-private [outputDir] into the shared `Movies/AuCamPRO` collection
     * via MediaStore (so it shows up in the gallery), then deletes the app-private copy.
     * No-op for [StorageLocation.AppPrivate]. Runs synchronously (blocking file I/O) — always
     * called after encoders/muxer are already fully stopped and drained, so callers are
     * responsible for making sure this doesn't run on the main thread: see
     * [stopRecordingInternalLockedAsync]'s doc for the real-device ANR this caused when it
     * didn't. [stopRecordingInternalLocked] (the [stopAll] teardown path) still calls this on
     * whatever thread it's on, which is acceptable there per its own doc.
     */
    private fun exportToPublicMoviesIfRequested(outputDir: File) {
        if (storageLocation != StorageLocation.PublicMovies) return
        val segmentFiles = outputDir.listFiles { f -> f.extension == "mp4" }?.sortedBy { it.name }
        if (segmentFiles.isNullOrEmpty()) return

        val resolver = context.contentResolver
        var lastExportedUri: android.net.Uri? = null
        for (file in segmentFiles) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AuCamPRO")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Log.e(TAG, "MediaStore insert failed for ${file.name}")
                    continue
                }
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                file.delete()
                Log.i(TAG, "Exported ${file.name} to Movies/AuCamPRO")
                lastExportedUri = uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export ${file.name} to MediaStore Movies", e)
            }
        }
        // §ギャラリー連携 — the *last* segment stands in for "this take" in the thumbnail
        // button, matching how a multi-segment recording is a single take from the
        // user's perspective even though it's several files on disk.
        lastExportedUri?.let { uri ->
            Handler(Looper.getMainLooper()).post { onMediaCaptured?.invoke(uri, true) }
        }
    }

    /**
     * Hi-res WAV counterpart of [exportToPublicMoviesIfRequested] (docs/HIRES_AUDIO_DESIGN.md
     * §6.4) — exports to `Music/AuCamPRO` via `MediaStore.Audio` rather than
     * `MediaStore.Video`/`Movies`, since a `.wav` isn't gallery content. Deliberately does
     * NOT touch [onMediaCaptured]/the gallery thumbnail — the MP4 segment already stands in
     * for "this take" there (see that method's own doc); the WAV is a supplementary
     * lossless master, not a second "capture" from the user's perspective. Same threading
     * contract as [exportToPublicMoviesIfRequested] (callers keep this off the main
     * thread).
     */
    private fun exportWavIfRequested(outputDir: File) {
        if (storageLocation != StorageLocation.PublicMovies) return
        val segmentFiles = outputDir.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }
        if (segmentFiles.isNullOrEmpty()) return

        val resolver = context.contentResolver
        for (file in segmentFiles) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/AuCamPRO")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Log.e(TAG, "MediaStore insert failed for ${file.name}")
                    continue
                }
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                file.delete()
                Log.i(TAG, "Exported ${file.name} to Music/AuCamPRO")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export ${file.name} to MediaStore Music", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Still-photo capture (§Photo mode)
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Fires a still-photo capture via [photoReader] (set up in [startPreview] alongside the
     * histogram reader) and saves the resulting JPEG to MediaStore `Pictures/AuCamPRO`
     * (always the public gallery location — unlike video's [StorageLocation] choice, a
     * one-off photo the user explicitly asked to capture is exactly the kind of content a
     * gallery app should show). No-op if not currently in a session with a live photo
     * reader (e.g. this device's preview session fell back to preview-only — see
     * [startPreview]'s fallback doc — or no preview has started yet).
     *
     * **PREVIEWING only, not while RECORDING**: [photoReader]'s surface is deliberately
     * never added to the *recording* session's surface set (only the plain preview
     * session's — see [startPreview]'s call site), the same reasoning as
     * [histogramReader] not being added there either. **実機で発見** — targeting a photo
     * capture at the currently-active session while a recording had reconfigured it to
     * `[previewSurface, video.inputSurface]` crashed outright
     * (`IllegalArgumentException: CaptureRequest contains unconfigured Input/Output
     * Surface!` — Camera2 requires every capture target to already be part of the
     * *current* session, and by then it wasn't). Adding a 3rd stream to the
     * recording session was judged not worth the risk given this exact hardware's already-
     * confirmed stream-combination fragility (see [supportedVideoConfigs]'s 3840x2880 doc)
     * — so this guards on PREVIEWING specifically rather than attempting it.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun capturePhoto() {
        if (pipelineState != PipelineState.PREVIEWING) return
        val reader = photoReader ?: run {
            Log.w(TAG, "capturePhoto: no photo reader available for this session")
            return
        }
        val factory = requestFactory ?: return
        val lens = selectedLens ?: return

        val characteristics = sessionController.characteristicsFor(lens.cameraId)
        val orientationHint = orientationTracker.orientationHintDegreesFor(characteristics)
        sessionController.capturePhoto(
            jpegSurface = reader.surface,
            requestFactory = factory,
            params = currentParams,
            jpegOrientation = orientationHint,
        )
    }

    /**
     * Long-press-to-focus on the preview (§4.1) — [normalizedX]/[normalizedY] are [0,1]
     * preview-view coordinates (top-left origin), already corrected by the caller for any
     * letterboxing between the on-screen view and the actual preview `Surface` bounds (see
     * [com.aucampro.recorder.camera.TapToMeteringRegion]'s doc for the full contract).
     * No-op if no preview session is active yet ([focusController] is null before the
     * first successful [startPreview]).
     */
    fun requestTapToFocus(normalizedX: Float, normalizedY: Float) {
        focusController?.onTap(normalizedX, normalizedY, System.nanoTime())
    }

    private fun savePhotoToMediaStore(bytes: ByteArray) {
        val resolver = context.contentResolver
        val fileName = "${APP_NAME_TAG}_IMG_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AuCamPRO")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.e(TAG, "MediaStore insert failed for photo")
            return
        }
        try {
            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            Log.i(TAG, "Photo saved: $fileName")
            Handler(Looper.getMainLooper()).post { onMediaCaptured?.invoke(uri, false) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save photo", e)
        }
    }

    /** Prefers the exact 3840x2160 size the recording path already proves works
     * concurrently with the fixed 16:9 preview buffer on this hardware (see
     * [supportedVideoConfigs]'s 3840x2880 doc for why an unproven size here is a real risk),
     * falling back to the largest available JPEG size if that exact one isn't offered. */
    private fun pickPhotoOutputSize(
        characteristics: android.hardware.camera2.CameraCharacteristics,
    ): android.util.Size? {
        val map = characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            as? android.hardware.camera2.params.StreamConfigurationMap ?: return null
        val sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: return null
        return sizes.firstOrNull { it.width == 3840 && it.height == 2160 }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun stopPreviewSession() {
        if (pipelineState == PipelineState.PREVIEWING) {
            sessionController.stop()
            pipelineState = PipelineState.IDLE
        }
    }

    /**
     * Rebuilds the live RECORDING-state capture session's surface set from the current
     * [previewSurface] (may be null — encoder-only) plus the active [videoEncoder]'s
     * InputSurface. Camera2 requires closing and reopening the session whenever the
     * output surface set changes (see [startRecording]'s doc) — same mechanism, just
     * triggered here by preview attach/detach instead of the initial preview→recording
     * transition.
     *
     * Caller must already hold [sessionMutex] (not reentrant — do not call this from
     * inside another `sessionMutex.withLock` block).
     *
     * Best-effort: recording is *not* aborted on failure here (e.g. a camera HAL
     * rejecting the resulting surface combination). A failure means the viewfinder may
     * not resume/detach cleanly, but the encoders keep running regardless — they don't
     * know or care whether the session reconfiguration succeeded, only whether frames
     * keep arriving at their InputSurface. If the HAL failure means frames *stop*
     * arriving, that surfaces as a silent stall rather than a reported error — there is
     * no `onEvent` channel threaded through preview attach/detach today. Full
     * crash/stall recovery is out of scope here (§Phase4b, not yet built).
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun reconfigureRecordingSurfacesLocked() {
        val video = videoEncoder ?: return
        val lens = selectedLens ?: return
        val factory = requestFactory ?: return
        try {
            sessionController.stop()
            sessionController.startRepeating(
                cameraId = lens.cameraId,
                outputSurfaces = listOfNotNull(previewSurface, video.inputSurface),
                requestFactory = factory,
                params = currentParams,
            )
            Log.i(TAG, "Recording session reconfigured (preview=${previewSurface != null})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconfigure recording session surfaces", e)
        }
    }

    private companion object {
        const val TAG = "RecordingPipeline"

        // Prefixes exported media filenames (segments, photos) with the app's brand name plus
        // a timestamp — both for gallery branding and to avoid MediaStore DISPLAY_NAME
        // collisions across separate recordings/captures.
        const val APP_NAME_TAG = "AuCamPRO"

        // Must match app/src/main/cpp/engine/OboeFullDuplexEngine.h's kSampleRate/kChannelCount.
        const val AUDIO_SAMPLE_RATE_HZ = 48_000
        const val AUDIO_CHANNEL_COUNT = 2
        // §4 originally specified 256kbps; raised to this device's actual AAC-LC encoder
        // ceiling — real-device query (SO-51C, `c2.android.aac.encoder`, the software AAC
        // encoder Android selects for this MIME/profile) via
        // `MediaCodecInfo.getCapabilitiesForType(MIMETYPE_AUDIO_AAC).audioCapabilities
        // .bitrateRange` returned [8000, 510000] — this is that range's ceiling. Other
        // devices/encoders may report a different range; `AudioEncoder.codec.configure()`
        // would throw if this exceeds whatever encoder gets selected there, so if this
        // constant is ever raised further it must be re-verified the same way rather than
        // guessed.
        const val AUDIO_BITRATE_BPS = 510_000
    }
}
