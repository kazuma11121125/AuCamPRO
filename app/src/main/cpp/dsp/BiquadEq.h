#pragma once

#include <array>
#include <cstddef>
#include <vector>

#include "common/TripleBuffer.h"

namespace procamera {

// RBJ Audio Cookbook peaking-EQ biquad coefficients (a0-normalized: a0 is always 1 and
// omitted). See https://www.w3.org/andrewz/audioeq-cookbook/audio-eq-cookbook.html.
struct BiquadCoeffs {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
};

BiquadCoeffs computeRbjPeakingCoeffs(double sampleRateHz, double centerFreqHz, double q, double gainDb);

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
    static constexpr int kRampSamples = 240;  // ~5ms @ 48kHz; see .cpp for rationale

    ThreeBandEq(double sampleRateHz, int channelCount);

    // UI thread only. band in [0, kNumBands). Recomputes that band's coefficients from
    // the given parametric values and republishes the full 3-band set.
    void setBandParams(int band, float freqHz, float q, float gainDb);

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

    static float processSample(float x, const BiquadCoeffs &c, FilterHistory *h);
    static BiquadCoeffs lerp(const BiquadCoeffs &a, const BiquadCoeffs &b, float t);

    double sampleRateHz_;
    int channelCount_;

    // UI-thread-owned source of truth; only ever touched from setBandParams().
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

}  // namespace procamera
