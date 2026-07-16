#include "dsp/MakeupGain.h"

#include <cmath>

namespace procamera {

void MakeupGain::setGainDb(float gainDb) {
    gainLinear_.store(std::pow(10.0f, gainDb / 20.0f), std::memory_order_relaxed);
}

void MakeupGain::process(float *interleaved, size_t sampleCount) const {
    const float g = gainLinear_.load(std::memory_order_relaxed);
    if (g == 1.0f) return;  // cheap bypass for the default (0dB / off) case
    for (size_t i = 0; i < sampleCount; ++i) {
        interleaved[i] *= g;
    }
}

}  // namespace procamera
