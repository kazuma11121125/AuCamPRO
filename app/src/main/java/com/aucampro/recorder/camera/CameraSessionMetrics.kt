package com.aucampro.recorder.camera

import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.aucampro.recorder.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 1 (measurement only, no behavior change) Camera2 session timing/counter
 * instrumentation — docs/CAMERA_SESSION_LATENCY_2026-07-21.md. [ENABLED] gates every public
 * function so a release build pays one static-final boolean check per call site and nothing
 * else.
 *
 * **Sync vs async Trace, and why it matters here**: `Trace.beginSection`/`endSection` are
 * thread-bound — the framework asserts strict nesting *on the calling thread*. Several of the
 * spans this app needs to measure cross a coroutine suspension (`openCamera()`/`createSession()`
 * suspend until a `CameraDevice.StateCallback`/`CameraCaptureSession.StateCallback` resumes them,
 * possibly on a different dispatcher thread than the one that called `beginSection`) — using the
 * sync API there would either assert-crash or silently attribute the span to the wrong thread.
 * [traceAsync] uses `beginAsyncSection`/`endAsyncSection` (API 29+, matching this app's
 * `minSdk`) instead, which correlate by an explicit cookie rather than thread identity. [traceSync]
 * is only for call sites proven not to suspend (verified per call site, not assumed).
 *
 * **Hot-path cost**: [onCaptureCallbackInvoked]/[recordCaptureCallbackDurationNanos] run on
 * every single `CameraCaptureSession.CaptureCallback.onCaptureCompleted()` (i.e. every captured
 * frame — up to 60/sec). Both are branch-free atomic-counter/array updates with **zero
 * allocation** (see [bucketIndexFor]'s manual loop instead of `indexOfFirst`, which would box a
 * lambda per call). `Trace.beginSection`/`endSection` calls themselves are only ever emitted for
 * a sampled 1-in-[CAPTURE_CALLBACK_TRACE_SAMPLE_EVERY] subset — see [shouldSample] — precisely
 * because *whether tracing is enabled or not*, wrapping a genuinely hot per-frame callback in
 * per-call section markers is unnecessary cost this measurement pass doesn't need to pay.
 */
object CameraSessionMetrics {

    // internal + @PublishedApi (not private): the public inline traceSync/traceAsync below
    // are inlined into caller bytecode across module/package boundaries, which the Kotlin
    // compiler disallows for a strictly-private member.
    @PublishedApi
    internal val ENABLED = BuildConfig.DEBUG

    private const val TAG = "CameraSessionMetrics"
    private const val CAPTURE_CALLBACK_TRACE_SAMPLE_EVERY = 30L
    private const val DUMP_INTERVAL_MS = 5_000L

    enum class RepeatingRequestReason {
        START_REPEATING, SESSION_RECONFIGURE, PARAM_UPDATE, FOCUS_REQUEST,
        // docs/PERSISTENT_ENCODER_SURFACE_DESIGN_2026-07-21.md Phase 2 — the
        // repeatingTargets-only reissue (CameraSessionController.updateRepeatingTargets)
        // used to add/remove the persistent encoder Surface on record start/stop without
        // paying createCaptureSession. Tracked separately from SESSION_RECONFIGURE so the
        // real-device feasibility pass can confirm this path is actually being taken
        // instead of silently falling back to a full reconfigure every time.
        REPEATING_TARGETS_UPDATE,
    }

    // ── Counters (Phase 1: observation only, nothing here changes any camera behavior) ──────
    private val openCameraCount = AtomicLong(0)
    private val createCaptureSessionCount = AtomicLong(0)
    private val captureRequestBuilderCount = AtomicLong(0)
    private val captureCallbackCount = AtomicLong(0)
    private val captureResultListenerCount = AtomicLong(0)

    // Always 0 in Phase 1 — no identical-params-skip logic exists yet (Phase 2 scope). Exposed
    // now so the dump format doesn't change shape once Phase 2 starts incrementing it.
    private val identicalParamsSkippedCount = AtomicLong(0)

    private val setRepeatingRequestCounts: Map<RepeatingRequestReason, AtomicLong> =
        RepeatingRequestReason.entries.associateWith { AtomicLong(0) }

    fun onOpenCamera() { if (ENABLED) openCameraCount.incrementAndGet() }
    fun onCreateCaptureSession() { if (ENABLED) createCaptureSessionCount.incrementAndGet() }
    fun onCaptureRequestBuilderCreated() { if (ENABLED) captureRequestBuilderCount.incrementAndGet() }
    fun onCaptureCallbackInvoked() { if (ENABLED) captureCallbackCount.incrementAndGet() }
    fun onCaptureResultListenerInvoked() { if (ENABLED) captureResultListenerCount.incrementAndGet() }

    fun onSetRepeatingRequest(reason: RepeatingRequestReason) {
        if (ENABLED) setRepeatingRequestCounts.getValue(reason).incrementAndGet()
    }

    fun setRepeatingRequestCount(reason: RepeatingRequestReason): Long =
        setRepeatingRequestCounts.getValue(reason).get()

    // ── Per-frame CaptureCallback duration histogram ─────────────────────────────────────────
    // Fixed buckets, never resized/sorted — see this object's class doc for why (must stay
    // cheap on the CameraCallback thread). Upper bound of each bucket, in nanoseconds.
    internal val HISTOGRAM_BOUNDS_NANOS = longArrayOf(
        25_000L, 50_000L, 100_000L, 250_000L, 500_000L,
        1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, Long.MAX_VALUE,
    )

    private val histogramBuckets = AtomicLongArray(HISTOGRAM_BOUNDS_NANOS.size)
    private val histogramCount = AtomicLong(0)
    private val histogramSumNanos = AtomicLong(0)
    private val histogramMaxNanos = AtomicLong(0)
    private val captureCallbackSampleTick = AtomicLong(0)

    /** Pure, host-testable (no Android framework dependency) — which fixed bucket a duration
     * falls into. Manual loop, not `indexOfFirst`, to avoid a per-call lambda allocation on
     * this object's hottest path. */
    internal fun bucketIndexFor(durationNanos: Long, bounds: LongArray): Int {
        for (i in bounds.indices) {
            if (durationNanos <= bounds[i]) return i
        }
        return bounds.lastIndex
    }

    /** Pure, host-testable — approximate percentile from fixed-bucket counts (upper bound of
     * the bucket where the cumulative count first reaches the target rank). Deliberately an
     * approximation, not an exact order statistic — matches the fixed-histogram design (no
     * stored samples to sort). */
    internal fun approxPercentileNanos(buckets: LongArray, totalCount: Long, bounds: LongArray, percentile: Double): Double {
        if (totalCount <= 0) return 0.0
        val target = totalCount * percentile
        var cumulative = 0L
        for (i in buckets.indices) {
            cumulative += buckets[i]
            if (cumulative >= target) {
                val upper = bounds[i]
                // Top bucket has no finite upper bound — report the previous bound doubled as
                // a rough "at least this much" indicator rather than Long.MAX_VALUE.
                return if (upper == Long.MAX_VALUE) {
                    bounds[bounds.lastIndex - 1].toDouble() * 2
                } else {
                    upper.toDouble()
                }
            }
        }
        return 0.0
    }

    /** Call on every CaptureCallback frame — cheap, no allocation, safe unconditionally. */
    fun recordCaptureCallbackDurationNanos(durationNanos: Long) {
        if (!ENABLED) return
        histogramCount.incrementAndGet()
        histogramSumNanos.addAndGet(durationNanos)
        var currentMax = histogramMaxNanos.get()
        while (durationNanos > currentMax && !histogramMaxNanos.compareAndSet(currentMax, durationNanos)) {
            currentMax = histogramMaxNanos.get()
        }
        histogramBuckets.incrementAndGet(bucketIndexFor(durationNanos, HISTOGRAM_BOUNDS_NANOS))
    }

    /** Whether the *next* Trace section for a per-frame hot path should actually be emitted —
     * false whenever tracing/metrics are off, otherwise true for 1-in-[CAPTURE_CALLBACK_TRACE_SAMPLE_EVERY]
     * frames. Callers use the single returned decision to gate all of that frame's sampled
     * sub-sections together (so e.g. `AuCam:captureCallbackSample` and `AuCam:focusResultSample`
     * light up on the same sampled frames, not independently-random ones). */
    fun shouldSampleThisFrame(): Boolean {
        if (!ENABLED || !Trace.isEnabled()) return false
        return captureCallbackSampleTick.incrementAndGet() % CAPTURE_CALLBACK_TRACE_SAMPLE_EVERY == 0L
    }

    /** For call sites that are already naturally low-frequency (e.g. the WB/AF passive
     * measurement block, already throttled to 1-in-10 frames by its own caller) and don't need
     * [shouldSampleThisFrame]'s additional 1-in-N sampling on top — still gated on tracing
     * actually being enabled so a Trace section is never opened for nothing. */
    fun tracingActive(): Boolean = ENABLED && Trace.isEnabled()

    // ── Sync/async Trace helpers ─────────────────────────────────────────────────────────────
    // See class doc for the sync-vs-async split. Both are no-ops (beyond the ENABLED check) in
    // release builds.

    /** Only for call sites verified NOT to suspend — a suspend call inside [block] can resume
     * on a different thread than the one that opened this section, which the sync Trace API
     * does not support. */
    inline fun <T> traceSync(name: String, block: () -> T): T {
        if (ENABLED && Trace.isEnabled()) {
            Trace.beginSection(name)
            try {
                return block()
            } finally {
                Trace.endSection()
            }
        }
        return block()
    }

    @PublishedApi
    internal val asyncCookieGen = AtomicInteger(0)

    /** For suspend call sites. Generates a fresh cookie per call (safe even if the same [name]
     * is in flight concurrently from multiple callers) and guarantees exactly one matching
     * `endAsyncSection` via `try/finally`, regardless of normal return, thrown exception, or
     * coroutine cancellation. */
    suspend inline fun <T> traceAsync(name: String, crossinline block: suspend () -> T): T {
        if (!ENABLED) return block()
        val cookie = asyncCookieGen.incrementAndGet()
        val tracing = Trace.isEnabled()
        if (tracing) Trace.beginAsyncSection(name, cookie)
        try {
            return block()
        } finally {
            if (tracing) Trace.endAsyncSection(name, cookie)
        }
    }

    // ── Recording-attempt-scoped spans ───────────────────────────────────────────────────────
    // "AuCam:startRecording" and its two long-lived children (first video frame / first muxer
    // sample can arrive tens to hundreds of ms after startRecording() itself returns, on
    // different threads — MediaCodec's callback thread and MuxerController's I/O executor
    // respectively) share one cookie: the recording attempt id. Single-writer assumption: this
    // app only ever has one recording in flight at a time.
    private val recordingAttemptIdGen = AtomicInteger(0)

    @Volatile
    private var currentRecordingAttemptId: Int = 0

    private val firstVideoFrameEnded = AtomicBoolean(true)
    private val firstMuxerVideoSampleEnded = AtomicBoolean(true)

    /** Call once at the top of `RecordingPipeline.startRecording()`. Returns the id to pass to
     * [endStartRecordingSpan]/[endFirstVideoFrame]/[endFirstMuxerVideoSample]. */
    fun beginRecordingAttempt(): Int {
        val id = recordingAttemptIdGen.incrementAndGet()
        currentRecordingAttemptId = id
        firstVideoFrameEnded.set(false)
        firstMuxerVideoSampleEnded.set(false)
        if (ENABLED && Trace.isEnabled()) {
            Trace.beginAsyncSection("AuCam:startRecording", id)
            Trace.beginAsyncSection("AuCam:recordingToFirstVideoFrame", id)
            Trace.beginAsyncSection("AuCam:recordingToFirstMuxerVideoSample", id)
        }
        logStage(id, "T0_startRecordingEntered")
        return id
    }

    /** Plain-text stage timestamp, once per named stage per recording attempt — deliberately
     * separate from the Trace spans above: extracting exact span durations back out of a
     * captured `.perfetto-trace` needs `trace_processor_shell` (not necessarily available in
     * every environment), whereas these lines are directly greppable from `adb logcat` with
     * no extra tooling. Low-frequency (10 calls per recording, at most) — safe to call from
     * whichever thread reaches each stage; never called from the CameraCallback thread. */
    fun logStage(id: Int, stage: String) {
        if (!ENABLED) return
        Log.i(TAG, "stage recordingAttemptId=$id stage=$stage tNanos=${SystemClock.elapsedRealtimeNanos()}")
    }

    /** The id [VideoEncoder]/[com.aucampro.recorder.muxer.MuxerController] read to close their
     * one-shot spans without a constructor-parameter change — single-recording-at-a-time
     * assumption, same as [beginRecordingAttempt]'s doc. */
    fun activeRecordingAttemptId(): Int = currentRecordingAttemptId

    /** Call once `startRecording()` itself has finished running — success or failure; this
     * only closes the outer "did the startRecording() call return" span, NOT the two
     * first-frame/first-sample spans, which are expected to still be open on success (frames
     * arrive shortly after this method returns, on a different thread). */
    fun endStartRecordingSpan(id: Int) {
        if (ENABLED && Trace.isEnabled()) {
            Trace.endAsyncSection("AuCam:startRecording", id)
        }
    }

    /** Force-closes the first-video-frame/first-muxer-sample spans if they never fired —
     * call from `startRecording()`'s failure path (no camera session ever ran, so no frame
     * will ever arrive) and defensively from the stop path, so a short-lived or failed
     * attempt never leaves a dangling open async section for the *next* attempt to inherit. */
    fun abortDanglingRecordingSpans(id: Int) {
        endFirstVideoFrame(id)
        endFirstMuxerVideoSample(id)
    }

    /** One-shot; safe to call more than once (e.g. from both [VideoEncoder] and a defensive
     * [endStartRecordingSpan] cleanup) — only the first call actually closes the span.
     * Returns whether *this* call was the one that closed it, so callers that also want a
     * one-shot [logStage] (e.g. [VideoEncoder]'s T8) can gate on it instead of relying on an
     * unrelated external guard to happen to also be one-shot. */
    fun endFirstVideoFrame(id: Int): Boolean {
        val wasFirst = firstVideoFrameEnded.compareAndSet(false, true)
        if (wasFirst && ENABLED && Trace.isEnabled()) Trace.endAsyncSection("AuCam:recordingToFirstVideoFrame", id)
        return wasFirst
    }

    /** One-shot; see [endFirstVideoFrame]'s doc. */
    fun endFirstMuxerVideoSample(id: Int): Boolean {
        val wasFirst = firstMuxerVideoSampleEnded.compareAndSet(false, true)
        if (wasFirst && ENABLED && Trace.isEnabled()) Trace.endAsyncSection("AuCam:recordingToFirstMuxerVideoSample", id)
        return wasFirst
    }

    // ── Periodic dump ─────────────────────────────────────────────────────────────────────────
    // Runs on Dispatchers.Default (never the CameraCallback HandlerThread, never Main) — the
    // string building and Log.i below are only acceptable off that hot thread, once per
    // DUMP_INTERVAL_MS. Caller (RecordingPipeline) starts this when a recording starts and
    // cancels the returned Job when it stops, matching this metrics object's own recording-attempt
    // scoping.
    fun startPeriodicDump(scope: CoroutineScope): Job? {
        if (!ENABLED) return null
        return scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(DUMP_INTERVAL_MS)
                dumpAndLog()
            }
        }
    }

    fun dumpAndLog() {
        if (!ENABLED) return
        val count = histogramCount.get()
        val sum = histogramSumNanos.get()
        val max = histogramMaxNanos.get()
        val buckets = LongArray(HISTOGRAM_BOUNDS_NANOS.size) { histogramBuckets.get(it) }
        val avgUs = if (count > 0) (sum.toDouble() / count) / 1000.0 else 0.0
        val p50Us = approxPercentileNanos(buckets, count, HISTOGRAM_BOUNDS_NANOS, 0.50) / 1000.0
        val p95Us = approxPercentileNanos(buckets, count, HISTOGRAM_BOUNDS_NANOS, 0.95) / 1000.0
        val maxUs = max / 1000.0
        val reasonsStr = RepeatingRequestReason.entries.joinToString(", ") {
            "${it.name}=${setRepeatingRequestCounts.getValue(it).get()}"
        }
        Log.i(
            TAG,
            "captureCallback: count=$count avgUs=%.1f p50Us=%.1f p95Us=%.1f maxUs=%.1f | ".format(avgUs, p50Us, p95Us, maxUs) +
                "openCamera=${openCameraCount.get()} createSession=${createCaptureSessionCount.get()} " +
                "builders=${captureRequestBuilderCount.get()} listenerInvoked=${captureResultListenerCount.get()} " +
                "identicalParamsSkipped=${identicalParamsSkippedCount.get()} setRepeatingRequest[$reasonsStr]",
        )
    }

    /** Test-only: clears all mutable state between test cases. Not for production use. */
    internal fun resetForTest() {
        openCameraCount.set(0)
        createCaptureSessionCount.set(0)
        captureRequestBuilderCount.set(0)
        captureCallbackCount.set(0)
        captureResultListenerCount.set(0)
        identicalParamsSkippedCount.set(0)
        setRepeatingRequestCounts.values.forEach { it.set(0) }
        histogramCount.set(0)
        histogramSumNanos.set(0)
        histogramMaxNanos.set(0)
        for (i in 0 until histogramBuckets.length()) histogramBuckets.set(i, 0)
        captureCallbackSampleTick.set(0)
    }
}
