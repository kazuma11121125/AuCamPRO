#include <jni.h>

#include <mutex>
#include <shared_mutex>
#include <unordered_map>
#include <vector>

#include "engine/OboeFullDuplexEngine.h"

// JNI bindings between com.aucampro.recorder.audio.NativeEngineBridge (Kotlin) and
// OboeFullDuplexEngine (C++). Every function here runs on a normal JVM thread (never the
// audio callback thread — see OboeFullDuplexEngine.h's threading contract), so JNI
// overhead and allocation here are fine; the RT constraints apply only inside
// OboeFullDuplexEngine::onAudioReady().
//
// Object lifetime uses the standard "native handle" pattern: nativeCreate() returns a
// pointer to a heap-allocated engine as a jlong; every other function takes that handle
// back. Kotlin owns calling nativeDestroy() exactly once (see NativeEngineBridge.kt).
//
// **実機で発見(2026-07-16 tombstone)**: that ownership contract alone isn't enough —
// AudioEncoder's drain thread (see AudioEncoder.kt's `stop()` doc) can still be mid
// `drainEncoderBuffer()` after `AudioEncoder.stop()`'s 3s join timeout elapses, while
// RecordingPipeline.stopAll() goes ahead and calls NativeEngineBridge.close() ->
// nativeDestroy() right after. That raced a live JNI call against `delete`d engine memory
// (SIGSEGV inside SpscRingBuffer::read, confirmed via a real device_app_native_crash
// tombstone: PERF_INVESTIGATION_2026-07-17.md §3.1). Kotlin's own `closed` flag
// (NativeEngineBridge.kt) only narrows that window — it's a plain check-then-call with no
// synchronization against a concurrent nativeDestroy(). g_registry below is the actual
// fix: every call resolves its handle under a shared lock (readers proceed concurrently,
// matching the previous zero-overhead direct-cast behavior), while nativeDestroy() takes
// the *exclusive* lock — so it cannot free the engine until every in-flight call above has
// already returned, and any call arriving after erase() sees a missing handle and no-ops
// instead of touching freed memory.

namespace {

std::shared_mutex g_registryMutex;
std::unordered_map<jlong, aucampro::OboeFullDuplexEngine *> g_registry;

// RAII handle resolver: shared-locks the registry for the lifetime of one JNI call.
// get() returns null if nativeDestroy() has already removed this handle (either it was
// never valid, or a `close()` raced this exact call) — every call site below must treat
// null as "engine already gone" and return its no-op/failure value rather than dereference.
class EngineGuard {
public:
    explicit EngineGuard(jlong handle) : lock_(g_registryMutex) {
        auto it = g_registry.find(handle);
        engine_ = it != g_registry.end() ? it->second : nullptr;
    }

    aucampro::OboeFullDuplexEngine *get() const { return engine_; }

private:
    std::shared_lock<std::shared_mutex> lock_;
    aucampro::OboeFullDuplexEngine *engine_;
};

jstring toJString(JNIEnv *env, const std::string &s) { return env->NewStringUTF(s.c_str()); }

constexpr jfloat kSilenceDb = -100.0f;  // matches NativeEngineBridge.kt's SILENCE_DB

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeCreate(JNIEnv *, jobject) {
    auto *engine = new aucampro::OboeFullDuplexEngine();
    const auto handle = reinterpret_cast<jlong>(engine);
    std::unique_lock lock(g_registryMutex);
    g_registry.emplace(handle, engine);
    return handle;
}

JNIEXPORT void JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeDestroy(JNIEnv *, jobject,
                                                                                            jlong handle) {
    aucampro::OboeFullDuplexEngine *engine = nullptr;
    {
        // Exclusive lock: blocks until every EngineGuard-holding call already in flight
        // for this handle has returned (see this file's class doc) before the erase
        // below makes the handle invisible to any new call, and before delete below
        // frees the memory those calls were reading/writing.
        std::unique_lock lock(g_registryMutex);
        auto it = g_registry.find(handle);
        if (it != g_registry.end()) {
            engine = it->second;
            g_registry.erase(it);
        }
    }
    delete engine;
}

// Returns null on success, an error description string on failure. requestedSampleRateHz
// drives the hi-res sample-rate fallback ladder (docs/HIRES_AUDIO_DESIGN.md §3) — see
// OboeFullDuplexEngine::start's doc; the actually-granted rate is queryable afterward via
// nativeGetActualSampleRate.
JNIEXPORT jstring JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeStart(
    JNIEnv *env, jobject, jlong handle, jint preferredInputDeviceId, jint requestedSampleRateHz) {
    EngineGuard guard(handle);
    if (guard.get() == nullptr) return toJString(env, "Engine already destroyed");
    auto result = guard.get()->start(preferredInputDeviceId, requestedSampleRateHz);
    if (result.isErr()) {
        return toJString(env, result.error());
    }
    return nullptr;
}

// The engine's actual current sample rate (after any hi-res fallback) — 0 if the engine
// has already been destroyed.
JNIEXPORT jint JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeGetActualSampleRate(
    JNIEnv *, jobject, jlong handle) {
    EngineGuard guard(handle);
    return guard.get() != nullptr ? guard.get()->sampleRateHz() : 0;
}

JNIEXPORT void JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeStop(JNIEnv *, jobject,
                                                                                         jlong handle) {
    EngineGuard guard(handle);
    if (guard.get() != nullptr) guard.get()->stop();
}

JNIEXPORT jstring JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeReopenInputStream(
    JNIEnv *env, jobject, jlong handle, jint deviceId) {
    EngineGuard guard(handle);
    if (guard.get() == nullptr) return toJString(env, "Engine already destroyed");
    auto result = guard.get()->reopenInputStream(deviceId);
    if (result.isErr()) {
        return toJString(env, result.error());
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeInsertSilence(
    JNIEnv *, jobject, jlong handle, jint frameCount) {
    EngineGuard guard(handle);
    if (guard.get() != nullptr) guard.get()->insertSilence(frameCount);
}

JNIEXPORT jstring JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeSetMonitoringEnabled(
    JNIEnv *env, jobject, jlong handle, jboolean enabled, jint outputDeviceId) {
    EngineGuard guard(handle);
    if (guard.get() == nullptr) return toJString(env, "Engine already destroyed");
    auto result = guard.get()->setMonitoringEnabled(enabled == JNI_TRUE, outputDeviceId);
    if (result.isErr()) {
        return toJString(env, result.error());
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeSetEqBandParams(
    JNIEnv *, jobject, jlong handle, jint band, jfloat freqHz, jfloat q, jfloat gainDb) {
    EngineGuard guard(handle);
    if (guard.get() != nullptr) guard.get()->setEqBandParams(band, freqHz, q, gainDb);
}

JNIEXPORT void JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeSetInputGainDb(
    JNIEnv *, jobject, jlong handle, jfloat gainDb) {
    EngineGuard guard(handle);
    if (guard.get() != nullptr) guard.get()->setInputGainDb(gainDb);
}

JNIEXPORT void JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeSetMakeupGainDb(
    JNIEnv *, jobject, jlong handle, jfloat gainDb) {
    EngineGuard guard(handle);
    if (guard.get() != nullptr) guard.get()->setMakeupGainDb(gainDb);
}

JNIEXPORT void JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeSetHighPassEnabled(
    JNIEnv *, jobject, jlong handle, jboolean enabled) {
    EngineGuard guard(handle);
    if (guard.get() != nullptr) guard.get()->setHighPassEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeSetHighPassCutoffHz(
    JNIEnv *, jobject, jlong handle, jfloat cutoffHz) {
    EngineGuard guard(handle);
    if (guard.get() != nullptr) guard.get()->setHighPassCutoffHz(cutoffHz);
}

JNIEXPORT jfloat JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativePeakDb(JNIEnv *, jobject,
                                                                                             jlong handle, jint channel) {
    EngineGuard guard(handle);
    return guard.get() != nullptr ? guard.get()->peakDb(channel) : kSilenceDb;
}

JNIEXPORT jfloat JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeRmsDb(JNIEnv *, jobject,
                                                                                            jlong handle, jint channel) {
    EngineGuard guard(handle);
    return guard.get() != nullptr ? guard.get()->rmsDb(channel) : kSilenceDb;
}

JNIEXPORT jint JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeRingBufferOverrunCount(
    JNIEnv *, jobject, jlong handle) {
    EngineGuard guard(handle);
    return guard.get() != nullptr ? guard.get()->ringBufferOverrunCount() : 0;
}

JNIEXPORT jint JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeHardwareXRunCount(JNIEnv *,
                                                                                                       jobject,
                                                                                                       jlong handle) {
    EngineGuard guard(handle);
    return guard.get() != nullptr ? guard.get()->hardwareXRunCount() : 0;
}

// Diagnostic (2026-07-18, monitor-noise investigation) — kept permanently, same as ringBufferOverrunCount/hardwareXRunCount — see
// OboeFullDuplexEngine::monitorWriteShortfallCount's doc.
JNIEXPORT jint JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeMonitorWriteShortfallCount(
    JNIEnv *, jobject, jlong handle) {
    EngineGuard guard(handle);
    return guard.get() != nullptr ? guard.get()->monitorWriteShortfallCount() : 0;
}

// Returns [framePosition, timeNanos] (CLOCK_MONOTONIC), or null if unavailable. See
// OboeFullDuplexEngine::getInputTimestamp for why this exists (a one-shot audio PTS
// anchor correlation, not a per-callback query).
JNIEXPORT jlongArray JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeGetInputTimestamp(
    JNIEnv *env, jobject, jlong handle) {
    EngineGuard guard(handle);
    if (guard.get() == nullptr) return nullptr;
    int64_t framePosition = 0;
    int64_t timeNanos = 0;
    if (!guard.get()->getInputTimestamp(&framePosition, &timeNanos)) {
        return nullptr;
    }
    jlongArray result = env->NewLongArray(2);
    const jlong values[2] = {static_cast<jlong>(framePosition), static_cast<jlong>(timeNanos)};
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

// Drains up to maxFrames stereo frames into dst (must be sized >= maxFrames * 2).
// Returns the number of frames actually read. This is the call the 2026-07-16 crash was
// in (AudioEncoder's drain thread, still draining after its owner gave up waiting for it
// — see this file's class doc): guard.get()==nullptr here is exactly that race, now a
// clean "nothing left to drain" instead of a freed-memory read.
JNIEXPORT jint JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeDrainEncoderBuffer(
    JNIEnv *env, jobject, jlong handle, jfloatArray dst, jint maxFrames) {
    EngineGuard guard(handle);
    if (guard.get() == nullptr) return 0;
    std::vector<float> scratch(static_cast<size_t>(maxFrames) * aucampro::OboeFullDuplexEngine::kChannelCount);
    const size_t framesRead = guard.get()->drainEncoderBuffer(scratch.data(), static_cast<size_t>(maxFrames));
    if (framesRead > 0) {
        env->SetFloatArrayRegion(dst, 0, static_cast<jsize>(framesRead * aucampro::OboeFullDuplexEngine::kChannelCount),
                                  scratch.data());
    }
    return static_cast<jint>(framesRead);
}

// Discards any stale backlog before a fresh AudioEncoder starts draining. See
// OboeFullDuplexEngine::flushRingBuffer's doc.
JNIEXPORT void JNICALL Java_com_aucampro_recorder_audio_NativeEngineBridge_nativeFlushRingBuffer(
    JNIEnv *, jobject, jlong handle) {
    EngineGuard guard(handle);
    if (guard.get() != nullptr) guard.get()->flushRingBuffer();
}

}  // extern "C"
