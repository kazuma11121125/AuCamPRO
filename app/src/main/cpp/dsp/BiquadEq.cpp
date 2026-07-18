#include "dsp/BiquadEq.h"

#include <cmath>

namespace aucampro {

BiquadCoeffs computeRbjPeakingCoeffs(double sampleRateHz, double centerFreqHz, double q, double gainDb) {
    // RBJ Audio Cookbook "Peaking EQ" formula.
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * M_PI * centerFreqHz / sampleRateHz;
    const double cosW0 = std::cos(w0);
    const double sinW0 = std::sin(w0);
    const double alpha = sinW0 / (2.0 * q);

    const double b0 = 1.0 + alpha * A;
    const double b1 = -2.0 * cosW0;
    const double b2 = 1.0 - alpha * A;
    const double a0 = 1.0 + alpha / A;
    const double a1 = -2.0 * cosW0;
    const double a2 = 1.0 - alpha / A;

    BiquadCoeffs out;
    out.b0 = static_cast<float>(b0 / a0);
    out.b1 = static_cast<float>(b1 / a0);
    out.b2 = static_cast<float>(b2 / a0);
    out.a1 = static_cast<float>(a1 / a0);
    out.a2 = static_cast<float>(a2 / a0);
    return out;
}

BiquadCoeffs computeRbjHighpassCoeffs(double sampleRateHz, double cutoffFreqHz, double q) {
    // RBJ Audio Cookbook "High Pass Filter" formula.
    const double w0 = 2.0 * M_PI * cutoffFreqHz / sampleRateHz;
    const double cosW0 = std::cos(w0);
    const double sinW0 = std::sin(w0);
    const double alpha = sinW0 / (2.0 * q);

    const double b0 = (1.0 + cosW0) / 2.0;
    const double b1 = -(1.0 + cosW0);
    const double b2 = (1.0 + cosW0) / 2.0;
    const double a0 = 1.0 + alpha;
    const double a1 = -2.0 * cosW0;
    const double a2 = 1.0 - alpha;

    BiquadCoeffs out;
    out.b0 = static_cast<float>(b0 / a0);
    out.b1 = static_cast<float>(b1 / a0);
    out.b2 = static_cast<float>(b2 / a0);
    out.a1 = static_cast<float>(a1 / a0);
    out.a2 = static_cast<float>(a2 / a0);
    return out;
}

BiquadCoeffs identityBiquadCoeffs() { return BiquadCoeffs{}; }  // b0=1, everything else 0 (see struct default)

ThreeBandEq::ThreeBandEq(double sampleRateHz, int channelCount)
    : sampleRateHz_(sampleRateHz), channelCount_(channelCount), historyPerChannel_(channelCount) {
    recomputeRampSamples();

    // Spec §4.2 defaults: Low 80Hz Q=0.8 -6dB / Mid 1500Hz Q=1.2 +3dB / High 8000Hz Q=0.7 -4dB.
    bandParams_[0] = {80.0f, 0.8f, -6.0f};
    bandParams_[1] = {1500.0f, 1.2f, 3.0f};
    bandParams_[2] = {8000.0f, 0.7f, -4.0f};
    for (int band = 0; band < kNumBands; ++band) {
        uiSideCoeffs_.bands[band] =
            computeRbjPeakingCoeffs(sampleRateHz_, bandParams_[band].freqHz, bandParams_[band].q, bandParams_[band].gainDb);
    }

    currentCoeffs_ = uiSideCoeffs_;
    rampStart_ = uiSideCoeffs_;
    rampTarget_ = uiSideCoeffs_;

    // Publish the initial state so the very first process() call (before any UI knob
    // interaction) already has a defined coefficient set to consume, keeping ramp state
    // consistent with what setBandParams() would produce.
    coeffExchange_.publish(uiSideCoeffs_);
}

void ThreeBandEq::setBandParams(int band, float freqHz, float q, float gainDb) {
    bandParams_[band] = {freqHz, q, gainDb};
    uiSideCoeffs_.bands[band] = computeRbjPeakingCoeffs(sampleRateHz_, freqHz, q, gainDb);
    coeffExchange_.publish(uiSideCoeffs_);
}

void ThreeBandEq::setSampleRate(double sampleRateHz) {
    sampleRateHz_ = sampleRateHz;
    recomputeRampSamples();
    for (int band = 0; band < kNumBands; ++band) {
        uiSideCoeffs_.bands[band] =
            computeRbjPeakingCoeffs(sampleRateHz_, bandParams_[band].freqHz, bandParams_[band].q, bandParams_[band].gainDb);
    }
    coeffExchange_.publish(uiSideCoeffs_);
}

void ThreeBandEq::recomputeRampSamples() {
    // ~5ms worth of samples at the current rate — was a fixed "240 (~5ms @ 48kHz)"
    // constant; docs/HIRES_AUDIO_DESIGN.md §6.5 calls out that literal as one of the
    // 48kHz-keyed spots that must scale with rate instead (96kHz would otherwise ramp in
    // ~2.5ms — still not a click, but not the intended ramp duration either).
    rampSamples_ = static_cast<int>(std::lround(sampleRateHz_ * 0.005));
}

BiquadCoeffs ThreeBandEq::lerp(const BiquadCoeffs &a, const BiquadCoeffs &b, float t) {
    BiquadCoeffs out;
    out.b0 = a.b0 + (b.b0 - a.b0) * t;
    out.b1 = a.b1 + (b.b1 - a.b1) * t;
    out.b2 = a.b2 + (b.b2 - a.b2) * t;
    out.a1 = a.a1 + (b.a1 - a.a1) * t;
    out.a2 = a.a2 + (b.a2 - a.a2) * t;
    return out;
}

float ThreeBandEq::processSample(float x, const BiquadCoeffs &c, FilterHistory *h) {
    const float y = c.b0 * x + c.b1 * h->x1 + c.b2 * h->x2 - c.a1 * h->y1 - c.a2 * h->y2;
    h->x2 = h->x1;
    h->x1 = x;
    h->y2 = h->y1;
    h->y1 = y;
    return y;
}

void ThreeBandEq::process(float *interleaved, size_t frameCount) {
    CoeffSet published;
    if (coeffExchange_.tryConsume(&published)) {
        // A new coefficient set arrived. Ramp starts from whatever is currently active
        // right now (which may itself be mid-ramp) — NOT from a frozen old value — so a
        // second knob tweak during an in-flight ramp never produces a discontinuity.
        rampStart_ = currentCoeffs_;
        rampTarget_ = published;
        rampSamplesRemaining_ = rampSamples_;
    }

    for (size_t frame = 0; frame < frameCount; ++frame) {
        if (rampSamplesRemaining_ > 0) {
            --rampSamplesRemaining_;
            if (rampSamplesRemaining_ == 0) {
                // Snap exactly to the target on the final ramp sample. The interpolated
                // t = 1 - remaining/kRampSamples formula never actually reaches 1.0 (its
                // last nonzero-remaining value is 1 - 1/kRampSamples), so without this the
                // filter would settle ~0.4% short of the requested response forever.
                currentCoeffs_ = rampTarget_;
            } else {
                const float t = 1.0f - static_cast<float>(rampSamplesRemaining_) / static_cast<float>(rampSamples_);
                for (int band = 0; band < kNumBands; ++band) {
                    currentCoeffs_.bands[band] = lerp(rampStart_.bands[band], rampTarget_.bands[band], t);
                }
            }
        }

        for (int ch = 0; ch < channelCount_; ++ch) {
            float sample = interleaved[frame * channelCount_ + ch];
            auto &history = historyPerChannel_[ch];
            for (int band = 0; band < kNumBands; ++band) {
                sample = processSample(sample, currentCoeffs_.bands[band], &history[band]);
            }
            interleaved[frame * channelCount_ + ch] = sample;
        }
    }
}

}  // namespace aucampro
