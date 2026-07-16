#include "dsp/HighPassFilter.h"

namespace procamera {

HighPassFilter::HighPassFilter(double sampleRateHz, int channelCount)
    : sampleRateHz_(sampleRateHz), channelCount_(channelCount), historyPerChannel_(channelCount) {
    // Starts disabled (identity/bypass) — matches CameraUiState.highPassEnabled's default.
    const BiquadCoeffs initial = identityBiquadCoeffs();
    currentCoeffs_ = initial;
    rampStart_ = initial;
    rampTarget_ = initial;
    coeffExchange_.publish(initial);
}

void HighPassFilter::setEnabled(bool enabled) {
    enabled_ = enabled;
    publishCurrentTarget();
}

void HighPassFilter::setCutoffHz(float cutoffHz) {
    cutoffHz_ = cutoffHz;
    if (enabled_) publishCurrentTarget();  // disabled: no audible effect, and avoids an
                                            // unnecessary ramp restart while off
}

void HighPassFilter::publishCurrentTarget() {
    const BiquadCoeffs target =
        enabled_ ? computeRbjHighpassCoeffs(sampleRateHz_, cutoffHz_, kQ) : identityBiquadCoeffs();
    coeffExchange_.publish(target);
}

BiquadCoeffs HighPassFilter::lerp(const BiquadCoeffs &a, const BiquadCoeffs &b, float t) {
    BiquadCoeffs out;
    out.b0 = a.b0 + (b.b0 - a.b0) * t;
    out.b1 = a.b1 + (b.b1 - a.b1) * t;
    out.b2 = a.b2 + (b.b2 - a.b2) * t;
    out.a1 = a.a1 + (b.a1 - a.a1) * t;
    out.a2 = a.a2 + (b.a2 - a.a2) * t;
    return out;
}

float HighPassFilter::processSample(float x, const BiquadCoeffs &c, FilterHistory *h) {
    const float y = c.b0 * x + c.b1 * h->x1 + c.b2 * h->x2 - c.a1 * h->y1 - c.a2 * h->y2;
    h->x2 = h->x1;
    h->x1 = x;
    h->y2 = h->y1;
    h->y1 = y;
    return y;
}

namespace {
bool isIdentity(const BiquadCoeffs &c) {
    return c.b0 == 1.0f && c.b1 == 0.0f && c.b2 == 0.0f && c.a1 == 0.0f && c.a2 == 0.0f;
}
}  // namespace

void HighPassFilter::process(float *interleaved, size_t frameCount) {
    BiquadCoeffs published;
    if (coeffExchange_.tryConsume(&published)) {
        // Ramp starts from whatever is currently active right now (possibly mid-ramp
        // itself) — see ThreeBandEq::process's identical reasoning for why.
        rampStart_ = currentCoeffs_;
        rampTarget_ = published;
        rampSamplesRemaining_ = kRampSamples;
    }

    // Cheap bypass for the steady-state disabled case (matches InputGain/MakeupGain's own
    // early-return for their "off" case) — real-device finding: without this, every sample
    // paid the full biquad cost even while off, unlike every other stage in this chain.
    // Deliberately checked against currentCoeffs_/rampSamplesRemaining_ (audio-thread-owned
    // state) rather than the UI-thread-owned enabled_ field — see that field's own
    // "only ever touched from setEnabled()/setCutoffHz()" comment.
    if (rampSamplesRemaining_ == 0 && isIdentity(currentCoeffs_)) return;

    for (size_t frame = 0; frame < frameCount; ++frame) {
        if (rampSamplesRemaining_ > 0) {
            --rampSamplesRemaining_;
            if (rampSamplesRemaining_ == 0) {
                currentCoeffs_ = rampTarget_;  // snap exactly to target on the final sample
            } else {
                const float t = 1.0f - static_cast<float>(rampSamplesRemaining_) / static_cast<float>(kRampSamples);
                currentCoeffs_ = lerp(rampStart_, rampTarget_, t);
            }
        }

        for (int ch = 0; ch < channelCount_; ++ch) {
            float &sample = interleaved[frame * channelCount_ + ch];
            sample = processSample(sample, currentCoeffs_, &historyPerChannel_[ch]);
        }
    }
}

}  // namespace procamera
