#pragma once

#include <array>
#include <cstddef>
#include <vector>

#include "common/TripleBuffer.h"

namespace aucampro {

// RBJ Audio Cookbook peaking-EQ biquad coefficients (a0-normalized: a0 is always 1 and
// omitted). See https://www.w3.org/andrewz/audioeq-cookbook/audio-eq-cookbook.html.
struct BiquadCoeffs {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
};

BiquadCoeffs computeRbjPeakingCoeffs(double sampleRateHz, double centerFreqHz, double q, double gainDb);

// RBJ Audio Cookbook "High Pass Filter" coefficients — see dsp/HighPassFilter.h for the
// smoothed, on/off-able wrapper around this that's actually wired into the DSP chain.
BiquadCoeffs computeRbjHighpassCoeffs(double sampleRateHz, double cutoffFreqHz, double q);

// The a0=1, b0=1, b1=b2=a1=a2=0 identity biquad (bit-exact passthrough) — used by
// dsp/HighPassFilter.h to represent "disabled" as just another coefficient set, so
// enabling/disabling can reuse the same click-free ramp mechanism as a cutoff-frequency
// change instead of needing a separate hard on/off switch.
BiquadCoeffs identityBiquadCoeffs();

// 3-band parametric peaking EQ (Low/Mid/High), processed in cascade. Coefficients are
// computed on the UI thread (per spec §4.2) from a knob change and handed to the audio
// callback thread via a lock-free TripleBuffer (see common/TripleBuffer.h). The audio
// thread never computes sin/cos/pow — only cheap linear interpolation between the last
// and newly-published coefficient sets, ramped over kRampSamples samples, so a knob
// change never produces a click: the filter's per-channel history (x1,x2,y1,y2) is never
// reset, only the coefficients slide smoothly underneath it.
class ThreeBandEq {
public:
    static constexpr int kNumBands = 3;

    ThreeBandEq(double sampleRateHz, int channelCount);

    // UI thread only. band in [0, kNumBands). Recomputes that band's coefficients from
    // the given parametric values and republishes the full 3-band set.
    void setBandParams(int band, float freqHz, float q, float gainDb);

    // UI thread only, engine-reconfigure context (docs/HIRES_AUDIO_DESIGN.md §4/§6.5) —
    // NOT a live per-sample-callback operation. Recomputes every band's coefficients (and
    // the ramp duration) for a new sample rate from each band's last-set freqHz/q/gainDb
    // (kept in [bandParams_] specifically so this doesn't need — and can't accidentally
    // drift from — a separate Kotlin-side "reapply after restart" step), then republishes.
    void setSampleRate(double sampleRateHz);

    // Audio callback thread only. In-place processes frameCount interleaved frames of
    // channelCount() channels each. RT-safe: no allocation, no locks.
    void process(float *interleaved, size_t frameCount);

    int channelCount() const { return channelCount_; }

private:
    struct CoeffSet {
        std::array<BiquadCoeffs, kNumBands> bands;
    };
    struct FilterHistory {
        float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    };
    struct BandParams {
        float freqHz;
        float q;
        float gainDb;
    };

    static float processSample(float x, const BiquadCoeffs &c, FilterHistory *h);
    static BiquadCoeffs lerp(const BiquadCoeffs &a, const BiquadCoeffs &b, float t);
    void recomputeRampSamples();

    double sampleRateHz_;
    int channelCount_;
    int rampSamples_;  // ~5ms worth of samples at sampleRateHz_; see .cpp for rationale

    // UI-thread-owned source of truth; only ever touched from setBandParams()/
    // setSampleRate(). bandParams_ is the *parametric* (freqHz/q/gainDb) form each band's
    // entry in uiSideCoeffs_ was last derived from — kept around specifically so
    // setSampleRate() can rederive uiSideCoeffs_ at the new rate without losing the user's
    // actual EQ settings (a plain destroy-and-reconstruct would silently reset every band
    // to its power-on default instead).
    std::array<BandParams, kNumBands> bandParams_;
    CoeffSet uiSideCoeffs_;
    TripleBuffer<CoeffSet> coeffExchange_;

    // Audio-thread-owned ramp state.
    CoeffSet rampStart_;
    CoeffSet rampTarget_;
    CoeffSet currentCoeffs_;
    int rampSamplesRemaining_ = 0;

    // Per-channel, per-band filter history. Sized once at construction (non-RT context);
    // process() never resizes it.
    std::vector<std::array<FilterHistory, kNumBands>> historyPerChannel_;
};

}  // namespace aucampro
