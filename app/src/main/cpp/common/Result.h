#pragma once

#include <optional>
#include <utility>
#include <variant>

namespace procamera {

// std::expected is C++23; this project is pinned to C++20 and built with
// -fno-exceptions (see app/build.gradle.kts), so this is a small, allocation-free
// substitute. Only non-throwing accessors (std::get_if) are used internally so this
// stays safe to use under -fno-exceptions even though std::variant is exception-aware
// in general.
template <typename T, typename E>
class Result {
public:
    static Result Ok(T value) { return Result(std::in_place_index<0>, std::move(value)); }
    static Result Err(E error) { return Result(std::in_place_index<1>, std::move(error)); }

    bool isOk() const { return storage_.index() == 0; }
    bool isErr() const { return storage_.index() == 1; }

    // Precondition: isOk(). Callers must check isOk()/isErr() first; there is no
    // exception-based safety net under -fno-exceptions.
    const T &value() const { return *std::get_if<0>(&storage_); }
    T &value() { return *std::get_if<0>(&storage_); }

    // Precondition: isErr().
    const E &error() const { return *std::get_if<1>(&storage_); }
    E &error() { return *std::get_if<1>(&storage_); }

    T valueOr(T fallback) const {
        if (const T *v = std::get_if<0>(&storage_)) {
            return *v;
        }
        return fallback;
    }

private:
    template <std::size_t I, typename U>
    Result(std::in_place_index_t<I> tag, U &&value) : storage_(tag, std::forward<U>(value)) {}

    std::variant<T, E> storage_;
};

// Specialization for operations that either succeed or produce an error, with no
// meaningful success payload (e.g. "engine started").
template <typename E>
class Result<void, E> {
public:
    static Result Ok() { return Result(std::nullopt); }
    static Result Err(E error) { return Result(std::move(error)); }

    bool isOk() const { return !error_.has_value(); }
    bool isErr() const { return error_.has_value(); }

    const E &error() const { return *error_; }

private:
    explicit Result(std::optional<E> error) : error_(std::move(error)) {}

    std::optional<E> error_;
};

}  // namespace procamera
