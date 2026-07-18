#pragma once

#include <cstddef>
#include <vector>

#include "common/TripleBuffer.h"
#include "dsp/BiquadEq.h"

namespace aucampro {

// Single-band, on/off-able high-pass filter (§4.2 "風切り音/ハンドリングノイズ対策の
// ローカット") — first in the DSP chain, before ThreeBandEq (see
// OboeFullDuplexEngine::onAudioReady): removing wind/rumble energy before it reaches the
// EQ means a boosted Low band never re-amplifies exactly what this stage is meant to
// remove, and doesn't eat into the gain-staging headroom the EQ/SafetyLimiter chain
// depends on (see SafetyLimiter.h's doc on that same headroom concern).
//
// Same lock-free UI-thread -> audio-thread coefficient handoff and click-free ramping
// scheme as ThreeBandEq (see its doc) — deliberately not shared code with it (this is a
// single band, not three, and factoring out a common base for two call sites wasn't
// judged worth the indirection), but the same RBJ Cookbook coefficient math via
// dsp/BiquadEq.h's free functions.
//
// Enable/disable reuses the ramp mechanism rather than a separate hard switch:
// "disabled" is just another coefficient set (the identity biquad, see
// identityBiquadCoeffs()), so toggling the filter on/off mid-recording ramps smoothly
// like any other parameter change instead of clicking.
class HighPassFilter {
public:
    HighPassFilter(double sampleRateHz, int channelCount);

    // UI thread only.
    void setEnabled(bool enabled);
    void setCutoffHz(float cutoffHz);

    // UI thread only, engine-reconfigure context (docs/HIRES_AUDIO_DESIGN.md §4/§6.5) —
    // NOT a live per-sample-callback operation. Recomputes the coefficient target (and the
    // ramp duration) for a new sample rate from the existing enabled_/cutoffHz_ state, so
    // a rate change doesn't silently reset this filter to disabled.
    void setSampleRate(double sampleRateHz);

    // Audio callback thread only. In-place, RT-safe (no allocation, no locks).
    void process(float *interleaved, size_t frameCount);

private:
    struct FilterHistory {
        float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    };

    void publishCurrentTarget();
    void recomputeRampSamples();
    static float processSample(float x, const BiquadCoeffs &c, FilterHistory *h);
    static BiquadCoeffs lerp(const BiquadCoeffs &a, const BiquadCoeffs &b, float t);

    double sampleRateHz_;
    int channelCount_;
    int rampSamples_;  // ~5ms worth of samples at sampleRateHz_; see .cpp for rationale

    // UI-thread-owned source of truth; only ever touched from setEnabled()/setCutoffHz().
    bool enabled_ = false;
    float cutoffHz_ = 100.0f;  // 風切り音対策の一般的な既定値(80-120Hz帯)
    static constexpr float kQ = 0.707f;  // Butterworth (maximally flat, no resonant peak)

    TripleBuffer<BiquadCoeffs> coeffExchange_;

    // Audio-thread-owned ramp state.
    BiquadCoeffs rampStart_;
    BiquadCoeffs rampTarget_;
    BiquadCoeffs currentCoeffs_;
    int rampSamplesRemaining_ = 0;

    // Per-channel filter history. Sized once at construction (non-RT context); process()
    // never resizes it.
    std::vector<FilterHistory> historyPerChannel_;
};

}  // namespace aucampro
