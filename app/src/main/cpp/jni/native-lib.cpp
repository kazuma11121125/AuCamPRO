#include <jni.h>

#include <vector>

#include "engine/OboeFullDuplexEngine.h"

// JNI bindings between com.procamera.recorder.audio.NativeEngineBridge (Kotlin) and
// OboeFullDuplexEngine (C++). Every function here runs on a normal JVM thread (never the
// audio callback thread — see OboeFullDuplexEngine.h's threading contract), so JNI
// overhead and allocation here are fine; the RT constraints apply only inside
// OboeFullDuplexEngine::onAudioReady().
//
// Object lifetime uses the standard "native handle" pattern: nativeCreate() returns a
// pointer to a heap-allocated engine as a jlong; every other function takes that handle
// back. Kotlin owns calling nativeDestroy() exactly once (see NativeEngineBridge.kt).

namespace {

procamera::OboeFullDuplexEngine *toEngine(jlong handle) {
    return reinterpret_cast<procamera::OboeFullDuplexEngine *>(handle);
}

jstring toJString(JNIEnv *env, const std::string &s) { return env->NewStringUTF(s.c_str()); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new procamera::OboeFullDuplexEngine());
}

JNIEXPORT void JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeDestroy(JNIEnv *, jobject,
                                                                                            jlong handle) {
    delete toEngine(handle);
}

// Returns null on success, an error description string on failure.
JNIEXPORT jstring JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeStart(
    JNIEnv *env, jobject, jlong handle, jint preferredInputDeviceId) {
    auto result = toEngine(handle)->start(preferredInputDeviceId);
    if (result.isErr()) {
        return toJString(env, result.error());
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeStop(JNIEnv *, jobject,
                                                                                         jlong handle) {
    toEngine(handle)->stop();
}

JNIEXPORT jstring JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeReopenInputStream(
    JNIEnv *env, jobject, jlong handle, jint deviceId) {
    auto result = toEngine(handle)->reopenInputStream(deviceId);
    if (result.isErr()) {
        return toJString(env, result.error());
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeInsertSilence(
    JNIEnv *, jobject, jlong handle, jint frameCount) {
    toEngine(handle)->insertSilence(frameCount);
}

JNIEXPORT jstring JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeSetMonitoringEnabled(
    JNIEnv *env, jobject, jlong handle, jboolean enabled, jint outputDeviceId) {
    auto result = toEngine(handle)->setMonitoringEnabled(enabled == JNI_TRUE, outputDeviceId);
    if (result.isErr()) {
        return toJString(env, result.error());
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeSetEqBandParams(
    JNIEnv *, jobject, jlong handle, jint band, jfloat freqHz, jfloat q, jfloat gainDb) {
    toEngine(handle)->setEqBandParams(band, freqHz, q, gainDb);
}

JNIEXPORT jfloat JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativePeakDb(JNIEnv *, jobject,
                                                                                             jlong handle) {
    return toEngine(handle)->peakDb();
}

JNIEXPORT jfloat JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeRmsDb(JNIEnv *, jobject,
                                                                                            jlong handle) {
    return toEngine(handle)->rmsDb();
}

JNIEXPORT jint JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeRingBufferOverrunCount(
    JNIEnv *, jobject, jlong handle) {
    return toEngine(handle)->ringBufferOverrunCount();
}

JNIEXPORT jint JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeHardwareXRunCount(JNIEnv *,
                                                                                                       jobject,
                                                                                                       jlong handle) {
    return toEngine(handle)->hardwareXRunCount();
}

// Returns [framePosition, timeNanos] (CLOCK_MONOTONIC), or null if unavailable. See
// OboeFullDuplexEngine::getInputTimestamp for why this exists (a one-shot audio PTS
// anchor correlation, not a per-callback query).
JNIEXPORT jlongArray JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeGetInputTimestamp(
    JNIEnv *env, jobject, jlong handle) {
    int64_t framePosition = 0;
    int64_t timeNanos = 0;
    if (!toEngine(handle)->getInputTimestamp(&framePosition, &timeNanos)) {
        return nullptr;
    }
    jlongArray result = env->NewLongArray(2);
    const jlong values[2] = {static_cast<jlong>(framePosition), static_cast<jlong>(timeNanos)};
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

// Drains up to maxFrames stereo frames into dst (must be sized >= maxFrames * 2).
// Returns the number of frames actually read.
JNIEXPORT jint JNICALL Java_com_procamera_recorder_audio_NativeEngineBridge_nativeDrainEncoderBuffer(
    JNIEnv *env, jobject, jlong handle, jfloatArray dst, jint maxFrames) {
    std::vector<float> scratch(static_cast<size_t>(maxFrames) * procamera::OboeFullDuplexEngine::kChannelCount);
    const size_t framesRead = toEngine(handle)->drainEncoderBuffer(scratch.data(), static_cast<size_t>(maxFrames));
    if (framesRead > 0) {
        env->SetFloatArrayRegion(dst, 0, static_cast<jsize>(framesRead * procamera::OboeFullDuplexEngine::kChannelCount),
                                  scratch.data());
    }
    return static_cast<jint>(framesRead);
}

}  // extern "C"
