package com.procamera.recorder.muxer

/**
 * Normalizes Video and Audio presentation timestamps onto a single epoch (recording
 * start = 0), in the units MediaCodec/MediaMuxer expect (microseconds). This is §4.3,
 * the spec's "最重要要件" (most important requirement) — see docs/ARCHITECTURE.md's PTS
 * synchronization design section for the full derivation this class implements.
 *
 * Not thread-safe: callers must confine video-side calls to the Video Encoder Callback
 * thread and audio-side calls to the Audio Encoder thread (matching the thread model in
 * docs/ARCHITECTURE.md); the two sides never touch shared mutable state with each other
 * except the immutable [recordingStartNanos] captured once in [start].
 *
 * **実機で修正済み(確信度の教訓)**: このクラスは当初、`CaptureRequest`の
 * `CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE`(REALTIME=CLOCK_BOOTTIME /
 * UNKNOWN=較正が必要)に応じて分岐する設計だった — `VideoEncoder`の
 * `bufferInfo.presentationTimeUs`が`CaptureResult.SENSOR_TIMESTAMP`と同じ生クロック
 * ドメインをそのまま伝播すると仮定していたため。実機(Sony SO-51C、
 * SENSOR_INFO_TIMESTAMP_SOURCE=REALTIME)での診断ログにより、この仮定は**誤り**と判明:
 * `presentationTimeUs`は実際には常に`System.nanoTime()`(CLOCK_MONOTONIC)ドメインで
 * 返ってくる(REALTIMEソース機でも)。`sensorTimestampNanos − presentationTimeUs×1000`
 * と`elapsedRealtimeNanos − nanoTime`(スリープ蓄積による約79591秒のギャップ)が
 * 1.7μs差で一致したことで確認済み。旧実装はこのギャップを二重に引いてしまい、
 * 較正後のPTSが常に大きく負になり0にクランプされ続け、結果的に最初の1フレーム
 * 以外すべて「非単調」として破棄されるバグを引き起こしていた(録画中ずっとVideo
 * フレームが1枚しかMuxerに書き込まれない、という形で実機録画テストで発覚)。
 * これを受け、REALTIME/UNKNOWN分岐・較正機構を全廃し、`presentationTimeUs`を
 * 単純に`recordingStartNanos`基準でゼロ点合わせするだけの実装に変更した——
 * Audioパス([startAudioAnchorFromFrameCorrelation]、`getInputTimestamp()`が
 * MONOTONICドメインと明記)と同じ基準系になるため、追加の較正なしに両トラックが
 * 素の捕捉時刻ベースで同期する、より単純かつ正確な設計になった。
 *
 * **既知の限界**: この単純化は1台の実機での検証結果に基づく。`presentationTimeUs`を
 * 生のHALセンサータイムスタンプのドメイン(CLOCK_BOOTTIME等)のまま素通しする
 * 別機種・別Codec実装が理論上存在し得る場合、このクラスはそのズレを検出せず
 * スリープ蓄積分だけ同期がずれる。Phase5で複数実機・複数ベンダーのCodecでの
 * 検証が必要。
 */
class PtsClockDomain(private val clock: Clock = SystemClockAdapter) {

    interface Clock {
        /** CLOCK_MONOTONIC — this class's chosen common reference domain for both tracks. */
        fun nanoTimeNanos(): Long
    }

    object SystemClockAdapter : Clock {
        override fun nanoTimeNanos(): Long = System.nanoTime()
    }

    private var recordingStartNanos: Long = 0L

    private var lastVideoPtsUs: Long = Long.MIN_VALUE
    private var audioAnchorNanos: Long = -1L
    private var lastAudioPtsUs: Long = Long.MIN_VALUE

    /** Call once, right when recording starts, before any PTS normalization. */
    fun start(nowNanos: Long = clock.nanoTimeNanos()) {
        recordingStartNanos = nowNanos
        lastVideoPtsUs = Long.MIN_VALUE
        lastAudioPtsUs = Long.MIN_VALUE
        audioAnchorNanos = -1L
    }

    /**
     * Normalizes one video frame's encoder-reported presentation time (already
     * CLOCK_MONOTONIC — see this class's doc) into an epoch-zeroed microsecond PTS
     * suitable for MediaCodec. Returns null if the computed PTS would not be strictly
     * greater than the last emitted one (the monotonic-increase guard from §4.3's last
     * bullet).
     */
    fun normalizeVideoPtsUs(presentationTimeNanos: Long): Long? {
        // A frame captured fractionally before start() (e.g. already in flight when
        // recording began) would otherwise map to a negative PTS, which MediaMuxer
        // rejects outright — clamp to the epoch boundary instead of emitting it raw.
        val ptsUs = ((presentationTimeNanos - recordingStartNanos) / 1000L).coerceAtLeast(0L)
        if (ptsUs <= lastVideoPtsUs) {
            return null
        }
        lastVideoPtsUs = ptsUs
        return ptsUs
    }

    /**
     * Call once when the first audio burst is received, anchoring the drift-free
     * sample-count-based Audio PTS basis (§4.3).
     *
     * Prefer [startAudioAnchorFromFrameCorrelation] over this raw form when a real
     * frame-position/time correlation is available (from
     * `NativeEngineBridge.getInputTimestamp()`): anchoring to "now" at the moment the
     * first callback happens to be *processed* dates sample 0 to callback wall-time,
     * which trails the sample's true capture instant by the audio input pipeline's
     * latency (tens of ms on real hardware) — a constant offset that silently eats into
     * the ±20ms A/V sync budget from frame one, even though the sample-count-based PTS
     * math downstream stays perfectly drift-free. This overload remains as the
     * documented fallback for when a correlation isn't available yet.
     */
    fun startAudioAnchor(nowNanos: Long = clock.nanoTimeNanos()) {
        audioAnchorNanos = nowNanos
    }

    /**
     * Anchors using a real (framePosition, timeNanos) correlation at CLOCK_MONOTONIC —
     * see [startAudioAnchor]'s doc for why this is preferred. Back-calculates the true
     * capture time of sample 0: anchor = timeNanos - framePosition/sampleRateHz.
     */
    fun startAudioAnchorFromFrameCorrelation(framePosition: Long, timeNanos: Long, sampleRateHz: Int) {
        val framePositionDurationNanos = framePosition * 1_000_000_000L / sampleRateHz
        startAudioAnchor(nowNanos = timeNanos - framePositionDurationNanos)
    }

    /**
     * Normalizes the cumulative sample count consumed so far into an epoch-zeroed
     * microsecond PTS. [cumulativeSampleCount] must be a running total (per-channel frame
     * count, i.e. not multiplied by channel count) since [startAudioAnchor]. Returns null
     * if the computed PTS would not be strictly greater than the last emitted one.
     */
    fun normalizeAudioPtsUs(cumulativeSampleCount: Long, sampleRateHz: Int): Long? {
        check(audioAnchorNanos >= 0) { "startAudioAnchor() must be called before normalizeAudioPtsUs()" }
        val elapsedNanos = cumulativeSampleCount * 1_000_000_000L / sampleRateHz
        val monotonicNanos = audioAnchorNanos + elapsedNanos
        val ptsUs = ((monotonicNanos - recordingStartNanos) / 1000L).coerceAtLeast(0L)
        if (ptsUs <= lastAudioPtsUs) {
            return null
        }
        lastAudioPtsUs = ptsUs
        return ptsUs
    }
}
