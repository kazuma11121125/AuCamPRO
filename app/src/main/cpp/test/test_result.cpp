#include <gtest/gtest.h>

#include "common/Result.h"

using procamera::Result;

TEST(ResultTest, OkCarriesValue) {
    auto r = Result<int, std::string>::Ok(42);
    EXPECT_TRUE(r.isOk());
    EXPECT_FALSE(r.isErr());
    EXPECT_EQ(r.value(), 42);
}

TEST(ResultTest, ErrCarriesError) {
    auto r = Result<int, std::string>::Err("boom");
    EXPECT_TRUE(r.isErr());
    EXPECT_FALSE(r.isOk());
    EXPECT_EQ(r.error(), "boom");
}

TEST(ResultTest, ValueOrFallsBackOnError) {
    auto r = Result<int, std::string>::Err("boom");
    EXPECT_EQ(r.valueOr(-1), -1);
    auto ok = Result<int, std::string>::Ok(7);
    EXPECT_EQ(ok.valueOr(-1), 7);
}

TEST(ResultVoidTest, OkAndErr) {
    auto ok = Result<void, std::string>::Ok();
    EXPECT_TRUE(ok.isOk());
    auto err = Result<void, std::string>::Err("bad");
    EXPECT_TRUE(err.isErr());
    EXPECT_EQ(err.error(), "bad");
}
