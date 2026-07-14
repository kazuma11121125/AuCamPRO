cmake_minimum_required(VERSION 3.22)
project(procamera_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Debug-only ASan/UBSan (§4.6). Never enabled for release: AGP passes this OFF explicitly
# for the release variant (see app/build.gradle.kts), and it defaults OFF here too so a
# plain `cmake --build` without AGP-supplied args never accidentally ships sanitized code.
option(PROCAMERA_ENABLE_SANITIZERS "Enable ASan+UBSan (debug builds only)" OFF)

add_library(procamera_native SHARED
    jni/native-lib.cpp
    engine/OboeFullDuplexEngine.cpp
    dsp/BiquadEq.cpp
    dsp/SafetyLimiter.cpp
    dsp/PeakRmsMeter.cpp
    dsp/InputGain.cpp
)

find_package(oboe REQUIRED CONFIG)

target_link_libraries(procamera_native
    PRIVATE
        oboe::oboe
        log
        android
)

target_include_directories(procamera_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

if(PROCAMERA_ENABLE_SANITIZERS)
    set(SANITIZER_FLAGS -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_compile_options(procamera_native PRIVATE ${SANITIZER_FLAGS})
    target_link_options(procamera_native PRIVATE ${SANITIZER_FLAGS})
endif()

target_compile_options(procamera_native PRIVATE
    -Wall -Wextra
    $<$<NOT:$<CONFIG:Debug>>:-Werror>
)
