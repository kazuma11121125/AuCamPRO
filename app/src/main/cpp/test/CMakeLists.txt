cmake_minimum_required(VERSION 3.22)
project(procamera_native_tests LANGUAGES CXX)

# Standalone HOST-build CMake project (deliberately separate from
# app/src/main/cpp/CMakeLists.txt, which cross-compiles for Android via the NDK). This
# project builds and runs on the development machine's own toolchain so the RT-critical,
# hardware-independent algorithms (lock-free ring buffer, triple buffer, Biquad EQ,
# limiter, meter) can be verified without an Android device — see docs/ARCHITECTURE.md
# "ネイティブ単体テストの前倒し" (§4.7's GTest targets, pulled forward into Phase 2).
#
# It only compiles the portable subset of app/src/main/cpp/: NOT engine/OboeFullDuplexEngine
# (depends on liboboe/AAudio) and NOT jni/native-lib.cpp (depends on JNI headers) — neither
# exists on a plain Linux host.
#
# Usage:
#   cmake -S app/src/main/cpp/test -B build-host-test -DCMAKE_BUILD_TYPE=Debug
#   cmake --build build-host-test -j
#   ctest --test-dir build-host-test --output-on-failure

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Debug)
endif()

# ASan+UBSan+TSan-friendly debug build by default on host: this is exactly where we can
# catch the ring buffer's memory-ordering bugs that would otherwise only surface as rare,
# unreproducible glitches on a real device.
option(PROCAMERA_TEST_SANITIZERS "Enable ASan+UBSan for the host test build" ON)

include(FetchContent)
FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/refs/tags/v1.17.0.tar.gz
)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

set(PROCAMERA_CPP_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/..)

add_library(procamera_portable STATIC
    ${PROCAMERA_CPP_ROOT}/dsp/BiquadEq.cpp
    ${PROCAMERA_CPP_ROOT}/dsp/SafetyLimiter.cpp
    ${PROCAMERA_CPP_ROOT}/dsp/PeakRmsMeter.cpp
    ${PROCAMERA_CPP_ROOT}/dsp/InputGain.cpp
    ${PROCAMERA_CPP_ROOT}/dsp/MakeupGain.cpp
    ${PROCAMERA_CPP_ROOT}/dsp/HighPassFilter.cpp
)
target_include_directories(procamera_portable PUBLIC ${PROCAMERA_CPP_ROOT})
target_compile_options(procamera_portable PUBLIC -Wall -Wextra)

if(PROCAMERA_TEST_SANITIZERS)
    set(TEST_SANITIZER_FLAGS -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_compile_options(procamera_portable PUBLIC ${TEST_SANITIZER_FLAGS})
    target_link_options(procamera_portable PUBLIC ${TEST_SANITIZER_FLAGS})
endif()

enable_testing()

add_executable(procamera_native_tests
    test_result.cpp
    test_ring_buffer.cpp
    test_triple_buffer.cpp
    test_biquad.cpp
    test_safety_limiter.cpp
    test_peak_rms_meter.cpp
    test_input_gain.cpp
)
target_link_libraries(procamera_native_tests PRIVATE procamera_portable GTest::gtest_main)

include(GoogleTest)
gtest_discover_tests(procamera_native_tests)
