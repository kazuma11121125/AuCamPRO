#pragma once

#include <cstddef>

namespace procamera {

// Look-ahead-less soft-knee clipper applied after the 3-band EQ (§4.2 "セイフティリミッ
// ター"). The EQ's own gain stages (e.g. Mid +3dB) can create digital-domain clipping that
// wasn't present at the input, so this stage exists purely to catch that — it is not a
// mastering-grade brickwall limiter.
//
// Below thresholdDbfs, the signal passes through bit-exact (y = x). Above it, a smooth
// saturating curve (tanh knee) asymptotically approaches 0dBFS instead of hard-clipping,
// which avoids the harsh harmonic content of a hard clip while still bounding the output.
// No look-ahead buffering is used (per spec, a simple implementation is acceptable here),
// so very fast transients can still overshoot the knee slightly before the curve catches
// them on the next sample — acceptable because this is a safety net, not the primary
// clipping-avoidance mechanism (input gain-staging guidance in §4.2 is that).
class SafetyLimiter {
public:
    explicit SafetyLimiter(float thresholdDbfs = -1.0f);

    // Audio callback thread only. In-place, RT-safe (no allocation, no branching on
    // anything but sample values).
    void process(float *interleaved, size_t sampleCount);

private:
    float thresholdLinear_;
};

}  // namespace procamera
