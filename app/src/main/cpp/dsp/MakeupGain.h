#pragma once

#include <atomic>
#include <cstddef>

namespace procamera {

// Optional, user-facing loudness boost — applied after the 3-band EQ but before
// SafetyLimiter (§4.2), so any overs it introduces are still caught by the limiter's
// safety net rather than escaping to the recorded file. Distinct from InputGain (see
// dsp/InputGain.h): InputGain sets how hot the signal hits the EQ/limiter chain (asymmetric
// range biased toward attenuation, for loud-venue use); this stage exists for the opposite
// scenario — a quiet source where the user wants the final recorded level louder than
// InputGain's own limited (+12dB max) boost headroom allows.
//
// Defaults to 0dB (bypass, bit-exact passthrough) and is expected to stay there for most
// recordings: like any digital gain applied after the noise floor is already fixed, it
// raises the noise floor by the same ratio it raises the signal — there is no way around
// that trade-off in the digital domain, so this is an opt-in control, not something to
// leave engaged by habit.
class MakeupGain {
public:
    void setGainDb(float gainDb);

    // Audio callback thread only. In-place, RT-safe (no allocation, no locking).
    void process(float *interleaved, size_t sampleCount) const;

private:
    std::atomic<float> gainLinear_{1.0f};
};

}  // namespace procamera
