#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <memory>

namespace procamera {

// Single-Producer Single-Consumer lock-free ring buffer of trivially-copyable elements.
//
// Producer (push) is called from the Oboe audio callback thread and MUST stay wait-free:
// no malloc/new/delete, no locks, no syscalls, bounded execution time (see §4.2's Audio
// Callback prohibitions). This class satisfies that by allocating its backing storage once
// in the constructor (called from a non-RT thread when the engine starts) and never
// resizing; push()/pop() only touch already-owned memory and atomics.
//
// Consumer (pop) is called from the dedicated Audio Encoder thread (not RT-constrained,
// but kept lock-free anyway since SPSC makes it free).
//
// -- False-sharing rationale --
// writeIndex_ is written only by the producer and read by the consumer; readIndex_ is
// written only by the consumer and read by the producer. If both atomics shared a single
// 64-byte cache line, every producer write would invalidate the consumer's cached copy of
// that line (and vice versa) even though the two threads never touch each other's field —
// pure MESI ping-pong with no logical need for it. On a mobile SoC this shows up as extra
// cache-coherency latency injected right into the audio callback's tight deadline. Padding
// each atomic to its own cache line (alignas(kCacheLineSize)) removes that false sharing.
//
// -- Memory ordering rationale --
// Producer, after copying new elements into the backing array, publishes the new
// writeIndex_ with memory_order_release. Consumer reads writeIndex_ with
// memory_order_acquire before reading the corresponding array slots. Per the C++ memory
// model, this release/acquire pair guarantees the consumer observes the producer's array
// writes that happened-before the release store — without it, the consumer could read
// stale/torn data despite seeing the updated index. The symmetric relationship holds for
// readIndex_ (consumer publishes with release; producer acquires it to know which slots
// are free to overwrite). This is intentionally NOT relaxed+cached: push()/pop() operate on
// whole audio-callback bursts (tens to hundreds of frames), not per-sample, so the
// acquire/release cost is amortized and not worth the added complexity of a
// locally-cached, periodically-refreshed opposite index.
template <typename T>
class SpscRingBuffer {
public:
    static_assert(std::is_trivially_copyable_v<T>, "SpscRingBuffer requires trivially copyable T");

    // capacityHint is rounded up to the next power of two so index wraparound can use a
    // bitmask instead of a modulo/division in the hot path.
    explicit SpscRingBuffer(size_t capacityHint)
        : capacity_(nextPowerOfTwo(capacityHint)),
          mask_(capacity_ - 1),
          buffer_(std::make_unique<T[]>(capacity_)) {}

    size_t capacity() const { return capacity_; }

    // Producer-only (audio callback thread). Returns the number of elements actually
    // written; less than count if the buffer doesn't have enough free space (overrun —
    // caller/engine is responsible for counting this as an xrun condition, never for
    // blocking to wait for space).
    size_t write(const T *src, size_t count) {
        const size_t writeIdx = writeIndex_.load(std::memory_order_relaxed);
        const size_t readIdx = readIndex_.load(std::memory_order_acquire);
        const size_t freeSpace = capacity_ - (writeIdx - readIdx);
        const size_t toWrite = count < freeSpace ? count : freeSpace;
        if (toWrite == 0) {
            return 0;
        }

        const size_t writePos = writeIdx & mask_;
        const size_t firstChunk = toWrite < (capacity_ - writePos) ? toWrite : (capacity_ - writePos);
        std::memcpy(&buffer_[writePos], src, firstChunk * sizeof(T));
        if (toWrite > firstChunk) {
            std::memcpy(&buffer_[0], src + firstChunk, (toWrite - firstChunk) * sizeof(T));
        }

        writeIndex_.store(writeIdx + toWrite, std::memory_order_release);
        return toWrite;
    }

    // Consumer-only (Audio Encoder thread). Returns the number of elements actually read.
    size_t read(T *dst, size_t count) {
        const size_t readIdx = readIndex_.load(std::memory_order_relaxed);
        const size_t writeIdx = writeIndex_.load(std::memory_order_acquire);
        const size_t available = writeIdx - readIdx;
        const size_t toRead = count < available ? count : available;
        if (toRead == 0) {
            return 0;
        }

        const size_t readPos = readIdx & mask_;
        const size_t firstChunk = toRead < (capacity_ - readPos) ? toRead : (capacity_ - readPos);
        std::memcpy(dst, &buffer_[readPos], firstChunk * sizeof(T));
        if (toRead > firstChunk) {
            std::memcpy(dst + firstChunk, &buffer_[0], (toRead - firstChunk) * sizeof(T));
        }

        readIndex_.store(readIdx + toRead, std::memory_order_release);
        return toRead;
    }

    // Approximate — only exact if called from the producer thread itself (readIndex_ can
    // move concurrently on the consumer side). Safe as a monitoring/UI hint (§4.4
    // backpressure detection), not as a synchronization primitive.
    size_t availableToRead() const {
        return writeIndex_.load(std::memory_order_acquire) - readIndex_.load(std::memory_order_acquire);
    }

    size_t availableToWrite() const { return capacity_ - availableToRead(); }

private:
    static constexpr size_t kCacheLineSize = 64;

    static size_t nextPowerOfTwo(size_t v) {
        size_t p = 1;
        while (p < v) {
            p <<= 1;
        }
        return p;
    }

    const size_t capacity_;
    const size_t mask_;
    std::unique_ptr<T[]> buffer_;

    alignas(kCacheLineSize) std::atomic<size_t> writeIndex_{0};
    alignas(kCacheLineSize) std::atomic<size_t> readIndex_{0};
};

}  // namespace procamera
