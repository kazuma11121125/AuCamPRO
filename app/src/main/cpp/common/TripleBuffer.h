#pragma once

#include <atomic>
#include <cstdint>
#include <type_traits>

namespace procamera {

// Wait-free single-writer/single-reader triple buffer for exchanging small POD state
// (e.g. EQ coefficients) between the UI/coroutine thread (writer) and the audio callback
// thread (reader) without locks, retries, or allocation.
//
// Why not a plain 2-slot "double buffer" with an atomic active-index?
// A naive double buffer (writer fills the inactive slot, then flips an atomic index) has
// a real write-tearing hazard here: if the writer publishes two updates back-to-back
// faster than the reader's next callback observes the first one, the writer's second
// update can start overwriting the exact slot the reader is still mid-read of, because a
// 2-slot scheme has no way to know "has the reader definitely moved off this slot yet".
// UI gesture callbacks (a fast slider drag) can plausibly fire faster than the audio
// callback's few-millisecond cadence, especially under scheduling jitter, so this isn't
// just a theoretical concern for §4.2's coefficient hand-off.
//
// The triple buffer removes the hazard structurally: the writer always writes into a slot
// that is neither the one currently exposed to the reader nor the one most recently
// handed off — there are always 3 slots for at most 2 "in flight" roles (writer-owned,
// reader-owned) plus one "unclaimed" spare, so the writer can never touch a slot the
// reader might still be reading. Both sides are wait-free: no CAS-retry loops, no
// possibility of the reader blocking on the writer.
template <typename T>
class TripleBuffer {
public:
    static_assert(std::is_trivially_copyable_v<T>, "TripleBuffer requires trivially copyable T");

    TripleBuffer() : slots_{}, flags_(kMiddleInit) {}

    // Writer-only (UI/coroutine thread). Publishes a new value; readers observe it on
    // their next tryConsume().
    void publish(const T &value) {
        slots_[writeIndex_] = value;
        const uint8_t newState = static_cast<uint8_t>(writeIndex_) | kDirtyBit;
        const uint8_t oldState = flags_.exchange(newState, std::memory_order_acq_rel);
        writeIndex_ = oldState & kIndexMask;
    }

    // Reader-only (audio callback thread). Returns true and writes *out if a newer value
    // has been published since the last successful consume; returns false (leaving *out
    // untouched) if nothing new is available. Never blocks, never allocates.
    bool tryConsume(T *out) {
        const uint8_t state = flags_.load(std::memory_order_acquire);
        if ((state & kDirtyBit) == 0) {
            return false;
        }
        const uint8_t newState = static_cast<uint8_t>(readIndex_);  // hand back readIndex_, dirty bit cleared
        const uint8_t oldState = flags_.exchange(newState, std::memory_order_acq_rel);
        readIndex_ = oldState & kIndexMask;
        *out = slots_[readIndex_];
        return true;
    }

private:
    static constexpr uint8_t kDirtyBit = 0b100;
    static constexpr uint8_t kIndexMask = 0b011;
    static constexpr uint8_t kMiddleInit = 1;  // slot 1 starts as the unclaimed middle slot

    T slots_[3];
    std::atomic<uint8_t> flags_;
    uint8_t writeIndex_ = 0;  // writer-owned, never accessed by reader
    uint8_t readIndex_ = 2;   // reader-owned, never accessed by writer
};

}  // namespace procamera
