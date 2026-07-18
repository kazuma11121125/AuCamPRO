#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>

#include <oboe/Oboe.h>

#include "buffer/SpscRingBuffer.h"
#include "common/Result.h"
#include "dsp/BiquadEq.h"
#include "dsp/HighPassFilter.h"
#include "dsp/InputGain.h"
#include "dsp/MakeupGain.h"
#include "dsp/PeakRmsMeter.h"
#include "dsp/SafetyLimiter.h"

namespace aucampro {

// Owns the Oboe input stream (mic capture, always on while recording) and an optional
// output stream (headphone monitor passthrough, §4.2). The input stream's audio callback
// is the sole RT thread in this class: onAudioReady() runs the DSP chain (input gain ->
// high-pass filter -> EQ -> makeup gain -> safety limiter -> meter) and pushes the result into a lock-free ring buffer that
// the (non-RT) Audio Encoder thread drains from Kotlin. Every method other than onAudioReady() /
// onErrorAfterClose() runs on a non-RT caller (UI/coroutine thread via JNI) and may take
// locks / allocate freely; onAudioReady() itself touches nothing but already-owned
// objects and their RT-safe methods.
class OboeFullDuplexEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    OboeFullDuplexEngine();
    ~OboeFullDuplexEngine() override;

    static constexpr int32_t kStandardSampleRate = 48000;
    // Hi-res ladder ceiling (docs/HIRES_AUDIO_DESIGN.md §2) — the ring buffer is sized for
    // this regardless of the currently-active rate (see kRingBufferCapacityFrames) so a
    // rate change never needs to resize/recreate it mid-session.
    static constexpr int32_t kMaxSampleRate = 192000;
    static constexpr int32_t kChannelCount = 2;
    // ~10 seconds of stereo audio headroom between the RT producer and the Encoder-thread
    // consumer at the *maximum* supported rate; generous on purpose since an overrun here
    // means lost audio, not just a dropped video frame. At kStandardSampleRate this is
    // correspondingly more headroom (~40s), which is harmless (docs/HIRES_AUDIO_DESIGN.md
    // §1's "ファイルサイズ非考慮" ruling extends to this ~15MB fixed allocation).
    static constexpr size_t kRingBufferCapacityFrames = static_cast<size_t>(kMaxSampleRate) * 10;

    // UI/coroutine thread only. Opens and starts the input stream (and the output stream
    // too, if monitoring was already requested) at [requestedSampleRateHz], implementing
    // both the SharingMode::Exclusive -> Shared and InputPreset::Unprocessed ->
    // VoiceRecognition fallback ladder from §4.2 AND (docs/HIRES_AUDIO_DESIGN.md §3) a
    // sample-rate fallback ladder (192000 -> 96000 -> kStandardSampleRate, entering at
    // whichever of those is <= [requestedSampleRateHz]) — kStandardSampleRate is always
    // the last rung and is guaranteed to succeed on any device this app already supports,
    // so this method never fails purely because a hi-res rate wasn't available; it only
    // fails for the same device-level reasons the pre-hi-res code could already fail for.
    // Every rate/SharingMode/InputPreset downgrade is logged — never silent. The actually-
    // granted rate is queryable afterward via [sampleRateHz].
    Result<void, std::string> start(int32_t preferredInputDeviceId,
                                     int32_t requestedSampleRateHz = kStandardSampleRate);

    // Any thread. The engine's actual current sample rate — what [start] most recently
    // succeeded at, after any fallback. UI/coroutine callers use this (via JNI) to display
    // what hi-res mode actually landed on rather than what was requested (same "never
    // silently fake it" principle as AudioDeviceRouter's device-label reporting).
    int32_t sampleRateHz() const { return sampleRateHz_; }

    // UI/coroutine thread only. Stops and closes both streams.
    void stop();

    // UI/coroutine thread only. Closes and reopens just the input stream on a new device
    // (§4.2 device hot-swap handling). The ring buffer / output stream / DSP state are
    // left untouched so recording continuity (and Audio PTS's cumulative-sample-count
    // basis) is preserved across the switch. Caller (Kotlin AudioDeviceRouter) is
    // responsible for measuring the wall-clock gap and calling insertSilence() to keep
    // the sample-count timeline continuous.
    Result<void, std::string> reopenInputStream(int32_t deviceId);

    // UI/coroutine thread only. Pushes frameCount frames of silence into the ring buffer,
    // e.g. to cover the gap while reopenInputStream() is in flight.
    void insertSilence(int32_t frameCount);

    // UI/coroutine thread only. Per §4.2, Kotlin's AudioDeviceRouter is the sole
    // authority on "is the current output device wired/USB headphones" — this method
    // trusts that decision and does not re-derive it; passing enabled=true while routed
    // to the built-in speaker would violate the anti-howling requirement, so callers must
    // gate this correctly before calling.
    //
    // enabled=false only flips the atomic flag onAudioReady() checks — it deliberately
    // does NOT close the output stream, because that stream may be concurrently
    // dereferenced by the (still-running) input callback for its passthrough write, and
    // Oboe streams are not documented as safe against write() on one thread racing
    // close() on another. The output stream is only actually closed in stop(), which
    // closes the input stream first — guaranteeing onAudioReady() cannot fire again —
    // before it is safe to close the output stream too. A practical consequence: once
    // opened, a monitor output stream stays open for the rest of the recording session
    // rather than being torn down and reopened on every toggle — but NOT idle while
    // disabled (2026-07-18, OFF→ON silence investigation): onAudioReady() keeps writing
    // silence to it even when this flag is false, since a LowLatency/None output stream
    // left started-but-completely-unfed risks the HAL tearing it down on some real
    // devices, which this method's "already open" fast path below would otherwise
    // silently reuse dead on the next enable (see onAudioReady()'s doc for the fix).
    Result<void, std::string> setMonitoringEnabled(bool enabled, int32_t outputDeviceId);

    // UI/coroutine thread only.
    void setEqBandParams(int band, float freqHz, float q, float gainDb);

    // UI/coroutine thread only. §4.2 風切り音/ハンドリングノイズ対策のローカット —
    // see dsp/HighPassFilter.h for why this is first in the chain (before the EQ) and how
    // enable/disable avoids a click.
    void setHighPassEnabled(bool enabled) { highPassFilter_.setEnabled(enabled); }
    void setHighPassCutoffHz(float cutoffHz) { highPassFilter_.setCutoffHz(cutoffHz); }

    // UI/coroutine thread only. Manual record-level control (audio.pdf調査の§Layer1相当
    // — InputPreset::Unprocessedで無効化したOSのAGCの代わりに、110-125dB SPLのような
    // 大音量ライブの現場でユーザー自身がレベルを追い込むための唯一の手段)。
    // See dsp/InputGain.h for what this can and cannot do.
    void setInputGainDb(float gainDb) { inputGain_.setGainDb(gainDb); }

    // UI/coroutine thread only. Optional post-EQ loudness boost, default 0dB/bypass — see
    // dsp/MakeupGain.h for how this differs from setInputGainDb above (opposite end of the
    // gain-staging range: boosting a source too quiet for InputGain's own limited headroom
    // to fully compensate, at the cost of also raising the noise floor).
    void setMakeupGainDb(float gainDb) { makeupGain_.setGainDb(gainDb); }

    // Any thread (JNI pull accessors). channel: 0 = left, 1 = right.
    float peakDb(int channel) const { return meter_.peakDb(channel); }
    float rmsDb(int channel) const { return meter_.rmsDb(channel); }
    int32_t ringBufferOverrunCount() const { return ringBufferOverrunCount_.load(std::memory_order_relaxed); }
    int32_t hardwareXRunCount() const;

    // Diagnostic (2026-07-18, monitor-noise investigation) — kept permanently, same as ringBufferOverrunCount/hardwareXRunCount — counts callbacks
    // where the monitor passthrough's non-blocking write() to the output stream
    // (timeoutNanoseconds=0) wrote fewer frames than the callback delivered, i.e. audio
    // was dropped from the monitor path specifically (distinct from
    // ringBufferOverrunCount, which tracks the separate mic->encoder ring buffer). No
    // equivalent counter existed for the monitor output before this; used to correlate
    // audible noise with actual dropped-frame events on a real device.
    int32_t monitorWriteShortfallCount() const { return monitorWriteShortfallCount_.load(std::memory_order_relaxed); }

    // UI/coroutine thread only — NOT the audio callback thread (Oboe's own docs advise
    // against calling getTimestamp() from onAudioReady, and it isn't needed there: this
    // exists to seed Kotlin's PtsClockDomain audio anchor once, shortly after start(),
    // not to be polled per callback). Correlates a frame position to a true CLOCK_MONOTONIC
    // capture time, which Kotlin uses to back-calculate the true capture time of sample 0
    // (anchorNanos = timeNanos - framePosition * 1e9 / sampleRate) — this avoids anchoring
    // to the wall-clock arrival time of the first callback, which would be offset from the
    // true capture time by the audio pipeline's input latency (§4.3's A/V sync budget is
    // tight enough that this offset matters; see docs/ARCHITECTURE.md). Returns false if
    // the platform/stream doesn't support it yet (e.g. queried too soon after start()).
    bool getInputTimestamp(int64_t *outFramePosition, int64_t *outTimeNanos) const;

    // Audio Encoder thread only. Drains up to maxFrames frames (interleaved stereo) from
    // the ring buffer; returns the number of frames actually read.
    size_t drainEncoderBuffer(float *dst, size_t maxFrames);

    // UI/coroutine thread only, before an Audio Encoder starts draining. Discards whatever
    // is currently sitting in the ring buffer (§4.2's persistent audio engine means it may
    // hold a stale backlog — up to kRingBufferCapacityFrames worth, frozen there since
    // nothing drains it during preview-only — or nothing at all if preview ran long enough
    // to overflow it and every write since has been silently dropped as an overrun). Either
    // way, a fresh AudioEncoder must start its cumulative-sample-count basis (§4.3) from the
    // stream's *current* frame position (see getInputTimestamp's framePosition), not from
    // whatever stale data happened to be sitting here — otherwise the PTS this recording
    // computes for its first frames lands seconds-to-minutes in the past relative to
    // recordingStartNanos and normalizeAudioPtsUs's monotonic guard silently drops them
    // until enough new frames drain to close that gap, which for a long preview-then-record
    // gap can silently eat the entire recording (real-device finding, see
    // docs/ARCHITECTURE.md).
    void flushRingBuffer();

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    Result<std::shared_ptr<oboe::AudioStream>, std::string> openInputStreamLocked(int32_t deviceId,
                                                                                     int32_t sampleRateHz);

    // Runtime engine rate (docs/HIRES_AUDIO_DESIGN.md §4/§6.5) — only ever written from
    // [start] (streamMutex_ held), after a stream has actually been granted this rate.
    // highPassFilter_/eq_/meter_ are kept in sync with it via their own setSampleRate()
    // calls in [start] (see that method) rather than being destroyed/reconstructed, so a
    // rate change doesn't lose the user's HighPass/EQ settings.
    int32_t sampleRateHz_ = kStandardSampleRate;

    InputGain inputGain_;
    HighPassFilter highPassFilter_;
    ThreeBandEq eq_;
    MakeupGain makeupGain_;
    SafetyLimiter limiter_;
    PeakRmsMeter meter_;
    SpscRingBuffer<float> ringBuffer_;  // interleaved stereo float samples

    // Guards stream open/close/reopen sequencing; never held during onAudioReady().
    std::mutex streamMutex_;
    // Only ever touched from UI-thread methods under streamMutex_; onAudioReady() reads
    // its `stream` parameter from Oboe directly and never dereferences this field.
    std::shared_ptr<oboe::AudioStream> inputStream_;

    // outputStream_ is read from onAudioReady() (the passthrough write), concurrently
    // with UI-thread writes from setMonitoringEnabled()/stop(). A plain shared_ptr here
    // would be a data race on the control block itself (UB) when one side reassigns
    // while the other dereferences. std::atomic<std::shared_ptr<T>> (C++20) would be the
    // textbook fix, but NDK r27d's libc++ does not implement that specialization —
    // verified empirically: instantiating it fails the library's own
    // is_trivially_copyable static_assert, i.e. it silently falls through to the
    // generic primary template instead of a real lock-free/spinlock shared_ptr
    // specialization. So instead: outputStream_ (shared_ptr) is UI-thread-only and owns
    // the object's lifetime (RAII); outputStreamRaw_ is a plain
    // std::atomic<oboe::AudioStream*> (a raw pointer is trivially copyable, so this *is*
    // genuinely lock-free on arm64/x86_64) that the audio thread reads. Safety does not
    // come from the atomic alone — it comes from the invariant that the pointee is never
    // destroyed while the input stream (and therefore onAudioReady()) might still be
    // running; see stop() and setMonitoringEnabled() for how that invariant is upheld.
    std::shared_ptr<oboe::AudioStream> outputStream_;
    std::atomic<oboe::AudioStream *> outputStreamRaw_{nullptr};

    std::atomic<bool> monitoringEnabled_{false};
    std::atomic<int32_t> ringBufferOverrunCount_{0};
    std::atomic<int32_t> monitorWriteShortfallCount_{0};  // TEMPORARY diagnostic, see getter's doc
};

}  // namespace aucampro
