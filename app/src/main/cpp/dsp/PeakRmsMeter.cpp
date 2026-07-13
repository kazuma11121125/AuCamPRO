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
        float frameAbsMax = 0.0f;
        float frameSumSq = 0.0f;
        for (int ch = 0; ch < channelCount; ++ch) {
            const float s = interleaved[frame * channelCount + ch];
            frameAbsMax = std::fmax(frameAbsMax, std::fabs(s));
            frameSumSq += s * s;
        }
        const float frameMeanSq = frameSumSq / static_cast<float>(channelCount);

        // Instant attack: jump up immediately if the new frame is louder than the
        // decaying peak. Exponential release otherwise.
        peakLinear_ = std::fmax(frameAbsMax, peakLinear_ * peakReleaseCoeffPerSample_);

        // One-pole smoothing of mean-square power (RMS ballistics).
        meanSquare_ = meanSquare_ * rmsSmoothingCoeffPerSample_ + frameMeanSq * (1.0f - rmsSmoothingCoeffPerSample_);
    }

    peakDb_.store(linearToDb(peakLinear_), std::memory_order_relaxed);
    rmsDb_.store(linearToDb(std::sqrt(meanSquare_)), std::memory_order_relaxed);
}

}  // namespace procamera
