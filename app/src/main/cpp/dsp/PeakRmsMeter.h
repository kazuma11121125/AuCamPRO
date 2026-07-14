#pragma once

#include <array>
#include <atomic>
#include <cstddef>

namespace procamera {

// Peak/RMS meter in dBFS, tracked independently per channel (L/R) rather than collapsed
// into a single combined value — a stereo mic signal can be much louder on one side (an
// off-axis performer, a monitor wedge bleeding into one capsule), and a combined meter
// hides that asymmetry from the user. process() runs on the audio callback thread
// (RT-safe: only atomic stores, no allocation/locks). peakDb()/rmsDb() are pull-accessors
// called from Kotlin via JNI on a Choreographer-driven ~60fps poll (§4.5) — pull rather
// than push specifically so the audio thread never calls into JNI (forbidden by §4.2); the
// UI side decides when it wants a fresh value instead of the audio thread deciding when to
// send one.
class PeakRmsMeter {
public:
    // This app never opens more than a stereo (2-channel) input stream (§4.2), so a
    // fixed-size per-channel array is simpler than a dynamically-sized one. process() with
    // channelCount < kMaxChannels (e.g. the mono case some host tests use) simply leaves
    // the unused channel(s) at the silence floor.
    static constexpr int kMaxChannels = 2;

    // releaseSeconds controls how quickly the displayed peak falls back down after a
    // transient (instant attack, exponential release — standard peak-meter ballistics).
    // rmsWindowSeconds controls the one-pole RMS smoothing time constant.
    PeakRmsMeter(double sampleRateHz, float releaseSeconds = 0.3f, float rmsWindowSeconds = 0.3f);

    // Audio callback thread only. channelCount must be <= kMaxChannels.
    void process(const float *interleaved, size_t frameCount, int channelCount);

    // Any thread (designed for JNI pull from the UI thread). channel must be in
    // [0, kMaxChannels).
    float peakDb(int channel) const { return peakDb_[channel].load(std::memory_order_relaxed); }
    float rmsDb(int channel) const { return rmsDb_[channel].load(std::memory_order_relaxed); }

private:
    static float linearToDb(float linear);

    float peakReleaseCoeffPerSample_;
    float rmsSmoothingCoeffPerSample_;

    // Audio-thread-owned running state (not shared; only the dBFS results below are
    // published for cross-thread reads).
    std::array<float, kMaxChannels> peakLinear_{};
    std::array<float, kMaxChannels> meanSquare_{};

    std::array<std::atomic<float>, kMaxChannels> peakDb_{-100.0f, -100.0f};
    std::array<std::atomic<float>, kMaxChannels> rmsDb_{-100.0f, -100.0f};
};

}  // namespace procamera
