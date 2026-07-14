#pragma once

#include <atomic>
#include <cstddef>

namespace procamera {

// Manual digital input-gain (record-level) stage. Applied first in the chain — before
// the EQ and SafetyLimiter — so the user's chosen level is what those downstream stages
// (and the PEAK/RMS meter) actually see, matching standard gain-staging practice (set
// levels first, shape/protect after).
//
// This exists because InputPreset::Unprocessed (§4.2) deliberately disables the OS's own
// AGC to avoid its unpredictable, undocumented behavior — but that also removes the only
// other mechanism that would otherwise adapt to a loud venue (110-125dB SPL live band)
// vs. a quiet one. Without this, the built-in mic's fixed analog gain is the only lever,
// and it is tuned for ordinary use, not a loud stage — so attenuation is the primary
// expected use for this app's target scenario (see the UI slider's asymmetric range).
//
// **確信度の明示**: これはADC(アナログ→デジタル変換)より*後段*のデジタルゲインで
// あり、マイク自体やADCの段階で既にクリップしている信号を救うことはできない
// (ソフトウェアでできるのは、既にデジタル化された値のスケーリングのみ)。また
// ブースト方向はノイズフロアも同じ比率で持ち上げる、あらゆるデジタルゲインに
// 共通のトレードオフを持つ。
//
// Thread-safe via std::atomic<float> — a single scalar value, so the TripleBuffer used
// by ThreeBandEq (which exchanges a whole coefficient set) would be unnecessary here.
class InputGain {
public:
    void setGainDb(float gainDb);

    // Audio callback thread only. In-place, RT-safe (no allocation, no locking).
    void process(float *interleaved, size_t sampleCount) const;

private:
    std::atomic<float> gainLinear_{1.0f};
};

}  // namespace procamera
