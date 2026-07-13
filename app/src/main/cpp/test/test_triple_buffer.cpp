#include <gtest/gtest.h>

#include <thread>

#include "common/TripleBuffer.h"

using procamera::TripleBuffer;

TEST(TripleBufferTest, ConsumeReturnsFalseWhenNothingPublishedYetSinceLastConsume) {
    TripleBuffer<int> tb;
    int value = -1;
    // Nothing published at all yet.
    EXPECT_FALSE(tb.tryConsume(&value));
}

TEST(TripleBufferTest, ConsumeSeesPublishedValue) {
    TripleBuffer<int> tb;
    tb.publish(42);
    int value = -1;
    EXPECT_TRUE(tb.tryConsume(&value));
    EXPECT_EQ(value, 42);
}

TEST(TripleBufferTest, SecondConsumeWithoutNewPublishReturnsFalse) {
    TripleBuffer<int> tb;
    tb.publish(1);
    int value = -1;
    ASSERT_TRUE(tb.tryConsume(&value));
    EXPECT_FALSE(tb.tryConsume(&value));  // no new publish since last consume
}

TEST(TripleBufferTest, MultiplePublishesBeforeConsumeOnlyExposeLatest) {
    TripleBuffer<int> tb;
    tb.publish(1);
    tb.publish(2);
    tb.publish(3);
    int value = -1;
    EXPECT_TRUE(tb.tryConsume(&value));
    EXPECT_EQ(value, 3);
    EXPECT_FALSE(tb.tryConsume(&value));
}

// Concurrent stress test: writer publishes a strictly increasing sequence; reader must
// never observe a value it has already observed-or-passed (monotonic non-decreasing), and
// every value it does observe must be a value that was actually published (never
// torn/corrupted data) — this is the safety property the wait-free 3-slot exchange in
// TripleBuffer.h's comment claims over a naive 2-slot double buffer.
TEST(TripleBufferTest, ConcurrentPublishConsumeNeverGoesBackwardsOrTears) {
    struct Payload {
        int64_t sequence;
        int64_t checksum;  // must always equal -sequence; corruption/tearing would break this
    };

    TripleBuffer<Payload> tb;
    std::atomic<bool> stop{false};
    constexpr int64_t kIterations = 2'000'000;

    std::thread writer([&] {
        for (int64_t i = 0; i < kIterations; ++i) {
            tb.publish(Payload{i, -i});
        }
        stop.store(true, std::memory_order_release);
    });

    std::thread reader([&] {
        int64_t lastSeen = -1;
        Payload p{};
        while (!stop.load(std::memory_order_acquire)) {
            if (tb.tryConsume(&p)) {
                ASSERT_EQ(p.checksum, -p.sequence) << "torn read detected";
                ASSERT_GE(p.sequence, lastSeen) << "value went backwards";
                lastSeen = p.sequence;
            }
        }
        // Drain any final value(s) published right before stop was observed.
        while (tb.tryConsume(&p)) {
            ASSERT_EQ(p.checksum, -p.sequence) << "torn read detected";
            ASSERT_GE(p.sequence, lastSeen) << "value went backwards";
            lastSeen = p.sequence;
        }
    });

    writer.join();
    reader.join();
}
