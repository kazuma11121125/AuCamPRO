#pragma once

#include <atomic>
#include <cstddef>

namespace procamera {

// Peak/RMS meter in dBFS. process() runs on the audio callback thread (RT-safe: only
// atomic stores, no allocation/locks). peakDb()/rmsDb() are pull-accessors called from
// Kotlin via JNI on a Choreographer-driven ~60fps poll (§4.5) — pull rather than push
// specifically so the audio thread never calls into JNI (forbidden by §4.2); the UI side
// decides when it wants a fresh value instead of the audio thread deciding when to send
// one.
class PeakRmsMeter {
public:
    // releaseSeconds controls how quickly the displayed peak falls back down after a
    // transient (instant attack, exponential release — standard peak-meter ballistics).
    // rmsWindowSeconds controls the one-pole RMS smoothing time constant.
    PeakRmsMeter(double sampleRateHz, float releaseSeconds = 0.3f, float rmsWindowSeconds = 0.3f);

    // Audio callback thread only.
    void process(const float *interleaved, size_t frameCount, int channelCount);

    // Any thread (designed for JNI pull from the UI thread).
    float peakDb() const { return peakDb_.load(std::memory_order_relaxed); }
    float rmsDb() const { return rmsDb_.load(std::memory_order_relaxed); }

private:
    static float linearToDb(float linear);

    float peakReleaseCoeffPerSample_;
    float rmsSmoothingCoeffPerSample_;

    // Audio-thread-owned running state (not shared; only the dBFS results below are
    // published for cross-thread reads).
    float peakLinear_ = 0.0f;
    float meanSquare_ = 0.0f;

    std::atomic<float> peakDb_{-100.0f};
    std::atomic<float> rmsDb_{-100.0f};
};

}  // namespace procamera
