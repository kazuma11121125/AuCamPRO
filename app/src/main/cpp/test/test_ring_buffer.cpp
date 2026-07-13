#include <gtest/gtest.h>

#include <atomic>
#include <numeric>
#include <thread>
#include <vector>

#include "buffer/SpscRingBuffer.h"

using procamera::SpscRingBuffer;

TEST(SpscRingBufferTest, CapacityRoundsUpToPowerOfTwo) {
    SpscRingBuffer<int> rb(10);
    EXPECT_EQ(rb.capacity(), 16u);
}

TEST(SpscRingBufferTest, WriteThenReadRoundTrip) {
    SpscRingBuffer<int> rb(8);
    const std::vector<int> input = {1, 2, 3, 4, 5};
    EXPECT_EQ(rb.write(input.data(), input.size()), input.size());

    std::vector<int> output(input.size());
    EXPECT_EQ(rb.read(output.data(), output.size()), output.size());
    EXPECT_EQ(input, output);
}

TEST(SpscRingBufferTest, WriteReturnsPartialCountWhenFull) {
    SpscRingBuffer<int> rb(4);  // rounds to 4
    std::vector<int> input(10);
    std::iota(input.begin(), input.end(), 0);

    // Buffer holds at most `capacity` elements before write starts refusing more (this
    // implementation does not overwrite unread data — see write()'s freeSpace check).
    const size_t written = rb.write(input.data(), input.size());
    EXPECT_LE(written, rb.capacity());
    EXPECT_GT(written, 0u);
}

TEST(SpscRingBufferTest, ReadReturnsZeroWhenEmpty) {
    SpscRingBuffer<int> rb(8);
    int out[4];
    EXPECT_EQ(rb.read(out, 4), 0u);
}

TEST(SpscRingBufferTest, WraparoundPreservesOrder) {
    SpscRingBuffer<int> rb(4);
    int scratch[4];

    // Fill, drain, refill repeatedly to force the write/read cursors past several
    // wraparounds and confirm ordering survives the two-memcpy wraparound path.
    int nextValue = 0;
    for (int cycle = 0; cycle < 20; ++cycle) {
        std::vector<int> batch = {nextValue, nextValue + 1, nextValue + 2};
        nextValue += 3;
        const size_t written = rb.write(batch.data(), batch.size());
        ASSERT_EQ(written, batch.size());

        const size_t read = rb.read(scratch, written);
        ASSERT_EQ(read, written);
        for (size_t i = 0; i < read; ++i) {
            EXPECT_EQ(scratch[i], batch[i]);
        }
    }
}

// Multithreaded stress test (per §4.7's explicit requirement for the ring buffer): one
// producer thread pushes a strictly increasing sequence of ints in small bursts (mimicking
// audio-callback-sized writes), one consumer thread drains and verifies it observes that
// same sequence with no loss, no duplication, and no reordering — this is the concrete
// safety property SpscRingBuffer promises under concurrent single-producer/single-consumer
// use with a buffer large enough to never fill (so no partial writes are expected).
TEST(SpscRingBufferTest, ConcurrentProducerConsumerPreservesSequence) {
    constexpr size_t kCapacity = 1 << 14;  // large enough to avoid backpressure at this rate
    constexpr int kTotalElements = 2'000'000;
    constexpr int kBurstSize = 191;  // deliberately not a power of two, to stress wraparound

    SpscRingBuffer<int> rb(kCapacity);
    std::atomic<bool> producerDone{false};

    std::thread producer([&] {
        int next = 0;
        std::vector<int> burst(kBurstSize);
        while (next < kTotalElements) {
            const int count = std::min(kBurstSize, kTotalElements - next);
            for (int i = 0; i < count; ++i) {
                burst[i] = next + i;
            }
            size_t written = 0;
            while (written < static_cast<size_t>(count)) {
                written += rb.write(burst.data() + written, count - written);
            }
            next += count;
        }
        producerDone.store(true, std::memory_order_release);
    });

    std::thread consumer([&] {
        int expected = 0;
        std::vector<int> scratch(kBurstSize);
        while (expected < kTotalElements) {
            const size_t got = rb.read(scratch.data(), kBurstSize);
            for (size_t i = 0; i < got; ++i) {
                ASSERT_EQ(scratch[i], expected) << "sequence violated at element " << expected;
                ++expected;
            }
        }
    });

    producer.join();
    consumer.join();
    EXPECT_TRUE(producerDone.load(std::memory_order_acquire));
}
