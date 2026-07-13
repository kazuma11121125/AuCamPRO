#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "dsp/SafetyLimiter.h"

using procamera::SafetyLimiter;

namespace {
float dbToLinear(float db) { return std::pow(10.0f, db / 20.0f); }
}  // namespace

TEST(SafetyLimiterTest, SignalBelowThresholdPassesThroughUnchanged) {
    SafetyLimiter limiter(-1.0f);
    const float thresholdLinear = dbToLinear(-1.0f);
    std::vector<float> samples = {0.0f, 0.1f, -0.2f, thresholdLinear * 0.99f, -thresholdLinear * 0.99f};
    const std::vector<float> original = samples;

    limiter.process(samples.data(), samples.size());

    for (size_t i = 0; i < samples.size(); ++i) {
        EXPECT_FLOAT_EQ(samples[i], original[i]);
    }
}

TEST(SafetyLimiterTest, SignalAboveThresholdIsCompressedNeverReachesFullScale) {
    SafetyLimiter limiter(-1.0f);
    std::vector<float> samples = {0.95f, -0.95f, 1.0f, -1.0f, 5.0f, -5.0f};

    limiter.process(samples.data(), samples.size());

    for (float s : samples) {
        EXPECT_LT(std::fabs(s), 1.0f) << "limiter output reached/exceeded 0dBFS";
    }
}

TEST(SafetyLimiterTest, CurveIsMonotonicAndSignPreserving) {
    SafetyLimiter limiter(-1.0f);
    std::vector<float> inputs;
    for (float x = 0.0f; x <= 3.0f; x += 0.05f) {
        inputs.push_back(x);
    }
    std::vector<float> outputs = inputs;
    limiter.process(outputs.data(), outputs.size());

    for (size_t i = 1; i < outputs.size(); ++i) {
        EXPECT_GE(outputs[i], outputs[i - 1]) << "limiter curve is not monotonic at index " << i;
    }

    std::vector<float> negatedOutputs;
    for (float x : inputs) {
        negatedOutputs.push_back(-x);
    }
    limiter.process(negatedOutputs.data(), negatedOutputs.size());
    for (size_t i = 0; i < outputs.size(); ++i) {
        EXPECT_NEAR(negatedOutputs[i], -outputs[i], 1e-6f) << "limiter is not odd-symmetric at index " << i;
    }
}
