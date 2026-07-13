#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>

#include <oboe/Oboe.h>

#include "buffer/SpscRingBuffer.h"
#include "common/Result.h"
#include "dsp/BiquadEq.h"
#include "dsp/PeakRmsMeter.h"
#include "dsp/SafetyLimiter.h"

namespace procamera {

// Owns the Oboe input stream (mic capture, always on while recording) and an optional
// output stream (headphone monitor passthrough, §4.2). The input stream's audio callback
// is the sole RT thread in this class: onAudioReady() runs the DSP chain (EQ -> safety
// limiter -> meter) and pushes the result into a lock-free ring buffer that the (non-RT)
// Audio Encoder thread drains from Kotlin. Every method other than onAudioReady() /
// onErrorAfterClose() runs on a non-RT caller (UI/coroutine thread via JNI) and may take
// locks / allocate freely; onAudioReady() itself touches nothing but already-owned
// objects and their RT-safe methods.
class OboeFullDuplexEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    OboeFullDuplexEngine();
    ~OboeFullDuplexEngine() override;

    static constexpr int32_t kSampleRate = 48000;
    static constexpr int32_t kChannelCount = 2;
    // ~10 seconds of stereo audio headroom between the RT producer and the Encoder-thread
    // consumer; generous on purpose since an overrun here means lost audio, not just a
    // dropped video frame.
    static constexpr size_t kRingBufferCapacityFrames = kSampleRate * 10;

    // UI/coroutine thread only. Opens and starts the input stream (and the output stream
    // too, if monitoring was already requested). Implements the SharingMode::Exclusive ->
    // Shared and InputPreset::Unprocessed -> VoiceRecognition fallback ladder from §4.2,
    // logging every downgrade (never silent).
    Result<void, std::string> start(int32_t preferredInputDeviceId);

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
    Result<void, std::string> setMonitoringEnabled(bool enabled, int32_t outputDeviceId);

    // UI/coroutine thread only.
    void setEqBandParams(int band, float freqHz, float q, float gainDb);

    // Any thread (JNI pull accessors).
    float peakDb() const { return meter_.peakDb(); }
    float rmsDb() const { return meter_.rmsDb(); }
    int32_t ringBufferOverrunCount() const { return ringBufferOverrunCount_.load(std::memory_order_relaxed); }
    int32_t hardwareXRunCount() const;

    // Audio Encoder thread only. Drains up to maxFrames frames (interleaved stereo) from
    // the ring buffer; returns the number of frames actually read.
    size_t drainEncoderBuffer(float *dst, size_t maxFrames);

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    Result<std::shared_ptr<oboe::AudioStream>, std::string> openInputStreamLocked(int32_t deviceId);

    ThreeBandEq eq_;
    SafetyLimiter limiter_;
    PeakRmsMeter meter_;
    SpscRingBuffer<float> ringBuffer_;  // interleaved stereo float samples

    // Guards stream open/close/reopen sequencing; never held during onAudioReady().
    std::mutex streamMutex_;
    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::shared_ptr<oboe::AudioStream> outputStream_;

    std::atomic<bool> monitoringEnabled_{false};
    std::atomic<int32_t> ringBufferOverrunCount_{0};
};

}  // namespace procamera
