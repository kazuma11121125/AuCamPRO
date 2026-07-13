#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "dsp/BiquadEq.h"

using procamera::BiquadCoeffs;
using procamera::computeRbjPeakingCoeffs;

namespace {

// Minimal standalone Direct-Form-I biquad, used only by this test to measure a single
// band's frequency response in isolation (ThreeBandEq itself always cascades 3 bands and
// ramps coefficients over time, which would conflate "did the math come out right" with
// "did the ramp settle" — this keeps the two concerns separate).
float applyBiquad(const BiquadCoeffs &c, float x, float *x1, float *x2, float *y1, float *y2) {
    const float y = c.b0 * x + c.b1 * (*x1) + c.b2 * (*x2) - c.a1 * (*y1) - c.a2 * (*y2);
    *x2 = *x1;
    *x1 = x;
    *y2 = *y1;
    *y1 = y;
    return y;
}

// Measures the steady-state gain (in dB) a biquad applies to a sine wave at freqHz,
// discarding an initial settling period to let filter transients die out.
double measureGainDb(const BiquadCoeffs &c, double sampleRateHz, double freqHz) {
    constexpr int kSettleCycles = 200;
    constexpr int kMeasureCycles = 200;
    const double omega = 2.0 * M_PI * freqHz / sampleRateHz;
    const int samplesPerCycle = static_cast<int>(sampleRateHz / freqHz);
    const int settleSamples = kSettleCycles * samplesPerCycle;
    const int measureSamples = kMeasureCycles * samplesPerCycle;

    float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    for (int n = 0; n < settleSamples; ++n) {
        const float x = static_cast<float>(std::sin(omega * n));
        applyBiquad(c, x, &x1, &x2, &y1, &y2);
    }

    double inSumSq = 0.0, outSumSq = 0.0;
    for (int n = settleSamples; n < settleSamples + measureSamples; ++n) {
        const float x = static_cast<float>(std::sin(omega * n));
        const float y = applyBiquad(c, x, &x1, &x2, &y1, &y2);
        inSumSq += static_cast<double>(x) * x;
        outSumSq += static_cast<double>(y) * y;
    }

    const double inRms = std::sqrt(inSumSq / measureSamples);
    const double outRms = std::sqrt(outSumSq / measureSamples);
    return 20.0 * std::log10(outRms / inRms);
}

}  // namespace

TEST(BiquadEqTest, PeakingFilterAppliesRequestedGainAtCenterFrequency) {
    constexpr double kSampleRate = 48000.0;
    constexpr double kCenterFreq = 1000.0;
    constexpr double kQ = 1.0;

    for (double gainDb : {-12.0, -6.0, 0.0, 3.0, 6.0, 12.0}) {
        BiquadCoeffs c = computeRbjPeakingCoeffs(kSampleRate, kCenterFreq, kQ, gainDb);
        const double measured = measureGainDb(c, kSampleRate, kCenterFreq);
        EXPECT_NEAR(measured, gainDb, 0.3) << "at gainDb=" << gainDb;
    }
}

TEST(BiquadEqTest, FarFromCenterFrequencyGainApproachesUnity) {
    constexpr double kSampleRate = 48000.0;
    constexpr double kCenterFreq = 1000.0;
    constexpr double kQ = 1.2;
    BiquadCoeffs c = computeRbjPeakingCoeffs(kSampleRate, kCenterFreq, kQ, 12.0);

    // A full octave-and-a-half below/above the boosted center, at this Q, the peaking
    // filter should have decayed back to within a fraction of a dB of unity.
    const double lowMeasured = measureGainDb(c, kSampleRate, kCenterFreq / 4.0);
    const double highMeasured = measureGainDb(c, kSampleRate, kCenterFreq * 4.0);
    EXPECT_NEAR(lowMeasured, 0.0, 1.0);
    EXPECT_NEAR(highMeasured, 0.0, 1.0);
}

TEST(BiquadEqTest, RampConvergesExactlyToRequestedGainAfterSettling) {
    // Regression test for a bug caught in review: the per-sample ramp interpolation
    // (t = 1 - remaining/kRampSamples) never reaches t=1 on its own, so without an
    // explicit snap-to-target on the final ramp sample, ThreeBandEq would settle ~0.4%
    // short of the requested coefficients forever. This drives the full
    // setBandParams() -> ramp -> cascade path (not the standalone biquad math used by
    // the other tests above), which is what actually ships.
    constexpr double kSampleRate = 48000.0;
    constexpr float kFreq = 1000.0f;
    constexpr float kQ = 1.0f;
    constexpr float kGainDb = 9.0f;
    constexpr int kMono = 1;

    procamera::ThreeBandEq eq(kSampleRate, kMono);
    // Flatten the other two bands to 0dB so only the band under test shapes the response.
    eq.setBandParams(0, 80.0f, 0.8f, 0.0f);
    eq.setBandParams(2, 8000.0f, 0.7f, 0.0f);
    eq.setBandParams(1, kFreq, kQ, kGainDb);

    // Run well past kRampSamples so the coefficient ramp has fully settled before
    // measurement begins.
    std::vector<float> warmup(procamera::ThreeBandEq::kRampSamples * 4, 0.0f);
    eq.process(warmup.data(), warmup.size());

    constexpr int kSettleCycles = 200;
    constexpr int kMeasureCycles = 200;
    const double omega = 2.0 * M_PI * kFreq / kSampleRate;
    const int samplesPerCycle = static_cast<int>(kSampleRate / kFreq);
    const int settleSamples = kSettleCycles * samplesPerCycle;
    const int measureSamples = kMeasureCycles * samplesPerCycle;

    std::vector<float> settle(settleSamples);
    for (int n = 0; n < settleSamples; ++n) {
        settle[n] = static_cast<float>(std::sin(omega * n));
    }
    eq.process(settle.data(), settle.size());

    std::vector<float> measure(measureSamples);
    for (int n = 0; n < measureSamples; ++n) {
        measure[n] = static_cast<float>(std::sin(omega * (settleSamples + n)));
    }
    const std::vector<float> input = measure;
    eq.process(measure.data(), measure.size());

    double inSumSq = 0.0, outSumSq = 0.0;
    for (int i = 0; i < measureSamples; ++i) {
        inSumSq += static_cast<double>(input[i]) * input[i];
        outSumSq += static_cast<double>(measure[i]) * measure[i];
    }
    const double measuredDb = 20.0 * std::log10(std::sqrt(outSumSq / measureSamples) / std::sqrt(inSumSq / measureSamples));
    EXPECT_NEAR(measuredDb, kGainDb, 0.3);
}

TEST(BiquadEqTest, DefaultThreeBandSpecValuesProduceStableOutput) {
    // Regression guard for the §4.2 default preset (Low 80Hz Q0.8 -6dB / Mid 1500Hz Q1.2
    // +3dB / High 8000Hz Q0.7 -4dB): construction must not produce NaN/Inf coefficients.
    procamera::ThreeBandEq eq(48000.0, 2);
    std::vector<float> block(256 * 2, 0.1f);
    // Run enough blocks to fully clear the coefficient ramp (kRampSamples=240) and
    // confirm steady-state processing stays finite.
    for (int i = 0; i < 10; ++i) {
        eq.process(block.data(), 256);
    }
    for (float sample : block) {
        EXPECT_TRUE(std::isfinite(sample));
    }
}
