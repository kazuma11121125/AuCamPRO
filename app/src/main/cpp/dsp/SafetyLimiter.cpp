#include "dsp/SafetyLimiter.h"

#include <cmath>

namespace procamera {

namespace {
// tanh() saturates to exactly 1.0f in float32 precision for large-but-finite arguments,
// so a knee curve of the form `threshold + (1-threshold)*tanh(x)` can output exactly
// 1.0 (0dBFS) for sufficiently loud input — not just "asymptotically approach" it. Since
// this stage exists specifically to prevent digital-domain overs, touching the true
// ceiling defeats the purpose (a later stage, e.g. TPDF dither on the 16-bit conversion,
// could then push it over). Targeting a ceiling strictly below 1.0 keeps the guarantee
// exact regardless of float32 tanh saturation.
constexpr float kCeilingLinear = 0.999f;
}  // namespace

SafetyLimiter::SafetyLimiter(float thresholdDbfs) : thresholdLinear_(std::pow(10.0f, thresholdDbfs / 20.0f)) {}

void SafetyLimiter::process(float *interleaved, size_t sampleCount) {
    for (size_t i = 0; i < sampleCount; ++i) {
        const float x = interleaved[i];
        const float mag = std::fabs(x);
        if (mag <= thresholdLinear_) {
            continue;
        }
        const float sign = x < 0.0f ? -1.0f : 1.0f;
        const float over = (mag - thresholdLinear_) / (kCeilingLinear - thresholdLinear_);
        interleaved[i] = sign * (thresholdLinear_ + (kCeilingLinear - thresholdLinear_) * std::tanh(over));
    }
}

}  // namespace procamera
