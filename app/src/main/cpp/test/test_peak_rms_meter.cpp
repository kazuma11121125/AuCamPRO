#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "dsp/PeakRmsMeter.h"

using procamera::PeakRmsMeter;

TEST(PeakRmsMeterTest, SineWaveConvergesToKnownRmsAndPeakDb) {
    constexpr double kSampleRate = 48000.0;
    constexpr float kAmplitude = 0.5f;
    constexpr double kFreq = 1000.0;
    constexpr int kChannels = 1;

    // releaseSeconds/rmsWindowSeconds shortened vs. production defaults so this test
    // doesn't need to simulate as much audio to reach steady state.
    PeakRmsMeter meter(kSampleRate, /*releaseSeconds=*/0.05f, /*rmsWindowSeconds=*/0.05f);

    const int totalSamples = static_cast<int>(kSampleRate * 2.0);  // 2 seconds, several time constants
    std::vector<float> block(4096);
    int produced = 0;
    int n = 0;
    while (produced < totalSamples) {
        const int count = std::min<int>(block.size(), totalSamples - produced);
        for (int i = 0; i < count; ++i, ++n) {
            block[i] = kAmplitude * static_cast<float>(std::sin(2.0 * M_PI * kFreq * n / kSampleRate));
        }
        meter.process(block.data(), count, kChannels);
        produced += count;
    }

    const float expectedRmsDb = 20.0f * std::log10(kAmplitude / std::sqrt(2.0f));
    const float expectedPeakDb = 20.0f * std::log10(kAmplitude);

    EXPECT_NEAR(meter.rmsDb(), expectedRmsDb, 0.5f);
    EXPECT_NEAR(meter.peakDb(), expectedPeakDb, 0.5f);
}

TEST(PeakRmsMeterTest, SilenceReadsAtOrBelowFloor) {
    PeakRmsMeter meter(48000.0);
    std::vector<float> block(4096, 0.0f);
    for (int i = 0; i < 20; ++i) {
        meter.process(block.data(), block.size() / 2, 2);
    }
    EXPECT_LE(meter.peakDb(), -99.0f);
    EXPECT_LE(meter.rmsDb(), -99.0f);
}

TEST(PeakRmsMeterTest, PeakReleaseDecaysAfterTransientEnds) {
    PeakRmsMeter meter(48000.0, /*releaseSeconds=*/0.05f);
    std::vector<float> loud(480, 0.9f);  // 10ms burst at 48kHz
    meter.process(loud.data(), loud.size() / 2, 2);
    const float peakAfterTransient = meter.peakDb();

    std::vector<float> silence(48000, 0.0f);  // 0.5s of silence at 48kHz stereo interleaved-equivalent count
    meter.process(silence.data(), silence.size() / 2, 2);
    const float peakAfterRelease = meter.peakDb();

    EXPECT_LT(peakAfterRelease, peakAfterTransient - 20.0f) << "peak did not decay after the transient ended";
}
