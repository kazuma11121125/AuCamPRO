#include "dsp/PeakRmsMeter.h"

#include <cmath>

namespace procamera {

namespace {
constexpr float kSilenceFloorDb = -100.0f;
}

PeakRmsMeter::PeakRmsMeter(double sampleRateHz, float releaseSeconds, float rmsWindowSeconds) {
    // One-pole coefficient: coeff = exp(-1 / (tau_seconds * sampleRate)).
    peakReleaseCoeffPerSample_ = std::exp(-1.0f / (releaseSeconds * static_cast<float>(sampleRateHz)));
    rmsSmoothingCoeffPerSample_ = std::exp(-1.0f / (rmsWindowSeconds * static_cast<float>(sampleRateHz)));
}

float PeakRmsMeter::linearToDb(float linear) {
    if (linear <= 0.0f) {
        return kSilenceFloorDb;
    }
    const float db = 20.0f * std::log10(linear);
    return db < kSilenceFloorDb ? kSilenceFloorDb : db;
}

void PeakRmsMeter::process(const float *interleaved, size_t frameCount, int channelCount) {
    for (size_t frame = 0; frame < frameCount; ++frame) {
        for (int ch = 0; ch < channelCount; ++ch) {
            const float s = interleaved[frame * channelCount + ch];
            const float absSample = std::fabs(s);

            // Instant attack: jump up immediately if the new sample is louder than the
            // decaying peak. Exponential release otherwise.
            peakLinear_[ch] = std::fmax(absSample, peakLinear_[ch] * peakReleaseCoeffPerSample_);

            // One-pole smoothing of mean-square power (RMS ballistics).
            meanSquare_[ch] = meanSquare_[ch] * rmsSmoothingCoeffPerSample_ + s * s * (1.0f - rmsSmoothingCoeffPerSample_);
        }
    }

    for (int ch = 0; ch < channelCount; ++ch) {
        peakDb_[ch].store(linearToDb(peakLinear_[ch]), std::memory_order_relaxed);
        rmsDb_[ch].store(linearToDb(std::sqrt(meanSquare_[ch])), std::memory_order_relaxed);
    }
}

}  // namespace procamera
