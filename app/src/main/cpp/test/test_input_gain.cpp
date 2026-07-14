#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "dsp/InputGain.h"

using procamera::InputGain;

namespace {
float dbToLinear(float db) { return std::pow(10.0f, db / 20.0f); }
}  // namespace

TEST(InputGainTest, DefaultIsUnityBypass) {
    InputGain gain;
    std::vector<float> samples = {0.0f, 0.1f, -0.2f, 0.5f, -0.9f};
    const std::vector<float> original = samples;

    gain.process(samples.data(), samples.size());

    for (size_t i = 0; i < samples.size(); ++i) {
        EXPECT_FLOAT_EQ(samples[i], original[i]);
    }
}

TEST(InputGainTest, PositiveGainScalesUpByExpectedLinearFactor) {
    InputGain gain;
    gain.setGainDb(6.0f);  // ~2x linear
    std::vector<float> samples = {0.1f, -0.2f, 0.3f};

    gain.process(samples.data(), samples.size());

    const float expectedFactor = dbToLinear(6.0f);
    EXPECT_NEAR(samples[0], 0.1f * expectedFactor, 1e-6f);
    EXPECT_NEAR(samples[1], -0.2f * expectedFactor, 1e-6f);
    EXPECT_NEAR(samples[2], 0.3f * expectedFactor, 1e-6f);
}

TEST(InputGainTest, NegativeGainAttenuatesByExpectedLinearFactor) {
    InputGain gain;
    gain.setGainDb(-12.0f);
    std::vector<float> samples = {0.8f, -0.4f};

    gain.process(samples.data(), samples.size());

    const float expectedFactor = dbToLinear(-12.0f);
    EXPECT_NEAR(samples[0], 0.8f * expectedFactor, 1e-6f);
    EXPECT_NEAR(samples[1], -0.4f * expectedFactor, 1e-6f);
}

TEST(InputGainTest, ZeroDbIsExactUnity) {
    InputGain gain;
    gain.setGainDb(0.0f);
    std::vector<float> samples = {0.37f, -0.81f};
    const std::vector<float> original = samples;

    gain.process(samples.data(), samples.size());

    for (size_t i = 0; i < samples.size(); ++i) {
        EXPECT_FLOAT_EQ(samples[i], original[i]);
    }
}

TEST(InputGainTest, LatestSetGainDbWinsOverEarlierCalls) {
    InputGain gain;
    gain.setGainDb(20.0f);
    gain.setGainDb(-6.0f);
    std::vector<float> samples = {1.0f};

    gain.process(samples.data(), samples.size());

    EXPECT_NEAR(samples[0], dbToLinear(-6.0f), 1e-6f);
}
