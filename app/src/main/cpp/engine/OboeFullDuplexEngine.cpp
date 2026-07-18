#include "engine/OboeFullDuplexEngine.h"

#include <ctime>
#include <vector>

#include "common/Log.h"

namespace aucampro {

OboeFullDuplexEngine::OboeFullDuplexEngine()
    : highPassFilter_(kStandardSampleRate, kChannelCount),
      eq_(kStandardSampleRate, kChannelCount),
      limiter_(-1.0f),
      meter_(kStandardSampleRate),
      ringBuffer_(kRingBufferCapacityFrames * kChannelCount) {}

OboeFullDuplexEngine::~OboeFullDuplexEngine() { stop(); }

Result<std::shared_ptr<oboe::AudioStream>, std::string> OboeFullDuplexEngine::openInputStreamLocked(
    int32_t deviceId, int32_t sampleRateHz) {
    // Fallback ladder per §4.2: try the ideal config first, then relax SharingMode, then
    // relax InputPreset. Every downgrade is logged — never silent.
    //
    // Hi-res capability detection (docs/HIRES_AUDIO_DESIGN.md §3): for a non-standard
    // (hi-res) request, sample-rate conversion is disabled below — Oboe's own docs note
    // "No conversion by Oboe. Underlying APIs may still do conversion", so this alone
    // isn't a hardware guarantee, but combined with this method's post-open
    // getSampleRate()-vs-requested guard (below) it's what makes [start]'s rate fallback
    // ladder trustworthy instead of silently accepting an OS-upsampled fake hi-res stream.
    // The standard (48kHz) path keeps the original Medium-quality conversion behavior
    // unchanged — that's the real-device-proven config this app already shipped with, and
    // still needs conversion allowed for e.g. a mono mic being channel-converted to stereo.
    const bool isHiRes = sampleRateHz != kStandardSampleRate;
    const oboe::SampleRateConversionQuality rateConversionQuality =
        isHiRes ? oboe::SampleRateConversionQuality::None : oboe::SampleRateConversionQuality::Medium;
    struct Attempt {
        oboe::SharingMode sharingMode;
        oboe::InputPreset inputPreset;
        const char *description;
    };
    const Attempt attempts[] = {
        // §4.2's InputPreset fallback ladder. R-channel investigation update (2026-07-16,
        // real-device): InputPreset was never the cause of the R-channel-silent finding —
        // switching it (Unprocessed <-> Camcorder) made no difference on real hardware,
        // confirmed with both the built-in mic and an external mic. The actual cause was
        // PerformanceMode::LowLatency (see below); reverted to Unprocessed-first since
        // that's genuinely the better default (avoids OS-level AGC fighting this app's own
        // InputGain/SafetyLimiter — see OboeFullDuplexEngine.h's doc) now that Camcorder's
        // only justification is gone.
        {oboe::SharingMode::Exclusive, oboe::InputPreset::Unprocessed, "Exclusive+Unprocessed"},
        {oboe::SharingMode::Shared, oboe::InputPreset::Unprocessed, "Shared+Unprocessed (SharingMode fallback)"},
        {oboe::SharingMode::Shared, oboe::InputPreset::VoiceRecognition,
         "Shared+VoiceRecognition (InputPreset fallback)"},
    };

    for (const Attempt &attempt : attempts) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
            // R-channel investigation (2026-07-16, real-device): the MMAP/low-latency
            // capture path on this hardware (Sony SO-51C) silently delivers only the
            // primary mic capsule — L has real signal, R sits at the noise floor
            // (~-98dBFS) — regardless of InputPreset, and regardless of built-in vs.
            // external mic, ruling out "the mic itself is mono". Sony's own Video Pro app
            // gets real stereo because it doesn't use this low-latency path. None (the
            // legacy/non-MMAP path) trades some input latency for correct stereo capture;
            // acceptable since A/V sync goes through PtsClockDomain's PTS math, not
            // wall-clock assumptions about this latency. Monitoring's output stream is
            // deliberately left on LowLatency below — this only affects capture.
            ->setPerformanceMode(oboe::PerformanceMode::None)
            ->setSharingMode(attempt.sharingMode)
            ->setFormat(oboe::AudioFormat::Float)
            ->setSampleRate(sampleRateHz)
            ->setChannelCount(kChannelCount)
            ->setInputPreset(attempt.inputPreset)
            ->setFormatConversionAllowed(true)
            ->setChannelConversionAllowed(true)
            ->setSampleRateConversionQuality(rateConversionQuality)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (deviceId != oboe::kUnspecified) {
            builder.setDeviceId(deviceId);
        }

        std::shared_ptr<oboe::AudioStream> stream;
        const oboe::Result openResult = builder.openStream(stream);
        if (openResult != oboe::Result::OK) {
            AUCAMPRO_LOGW("openInputStream attempt [%s] failed: %s", attempt.description,
                            oboe::convertToText(openResult));
            continue;
        }

        if (&attempt != &attempts[0]) {
            AUCAMPRO_LOGW("Input stream opened via fallback: %s", attempt.description);
        }
        // The device may still silently grant a different config than requested even on
        // success (documented AAudio behavior) — surface that too rather than assume.
        if (stream->getSharingMode() != attempt.sharingMode) {
            AUCAMPRO_LOGW("Input stream SharingMode downgraded by OS to %d",
                            static_cast<int>(stream->getSharingMode()));
        }
        if (stream->getInputPreset() != attempt.inputPreset) {
            AUCAMPRO_LOGW("Input stream InputPreset downgraded by OS to %d",
                            static_cast<int>(stream->getInputPreset()));
        }

        // The entire DSP chain (EQ/limiter/meter) and the ring buffer's frame math
        // hard-assume kChannelCount/sampleRateHz interleaved float frames. setFormat/
        // SampleRate/ChannelConversionAllowed(true) above ask Oboe to make that true
        // regardless of the device's native config, but this has not been verified on a
        // real device (§ "確信度の明示" — no hardware available in this environment), so
        // fail loudly here rather than silently misinterpret e.g. a mono stream as
        // interleaved stereo (which would corrupt every sample, not just degrade quality).
        if (stream->getChannelCount() != kChannelCount || stream->getSampleRate() != sampleRateHz) {
            AUCAMPRO_LOGE("Input stream opened with unexpected config: channels=%d rate=%d (expected %d/%d)",
                            stream->getChannelCount(), stream->getSampleRate(), kChannelCount, sampleRateHz);
            stream->close();
            return Result<std::shared_ptr<oboe::AudioStream>, std::string>::Err(
                "Input stream channel/sample-rate conversion did not produce the expected format");
        }

        // TEMPORARY diagnostic (2026-07-15 real-device R-channel investigation) — logs
        // which physical input device AAudio actually routed to, and its declared channel
        // mask, so this can be cross-referenced against `dumpsys audio`'s device list to
        // check whether the "stereo" stream we open is genuinely a 2-mic array device or a
        // mono device AAudio is silently channel-converting (which would explain a
        // consistently-silent second channel far better than the InputPreset choice does).
        AUCAMPRO_LOGI("Input stream opened: deviceId=%d channelCount=%d sampleRate=%d",
                        stream->getDeviceId(), stream->getChannelCount(), stream->getSampleRate());

        return Result<std::shared_ptr<oboe::AudioStream>, std::string>::Ok(stream);
    }

    return Result<std::shared_ptr<oboe::AudioStream>, std::string>::Err(
        "All input stream open attempts failed (Exclusive/Shared x Unprocessed/VoiceRecognition)");
}

Result<void, std::string> OboeFullDuplexEngine::start(int32_t preferredInputDeviceId,
                                                          int32_t requestedSampleRateHz) {
    std::lock_guard<std::mutex> lock(streamMutex_);

    // 実機で発見 (2026-07-18, monitor-noise investigation): this used to assign straight
    // into inputStream_/leave outputStream_ untouched, silently trusting that callers never
    // call [start] again while a stream from a previous call is still live. That assumption
    // is false in practice — confirmed on-device via logcat, this app's own init sequence
    // opens the input stream once at the default 48kHz, opens the monitor output stream
    // (if monitoring was restored on from saved settings) against *that* rate, and only
    // *then* re-calls [start] at the user's actual saved Hi-Res rate, with no [stop] in
    // between. The old inputStream_ leaked (never closed — its callback could in theory
    // still fire concurrently with the new stream's), and the monitor output stream kept
    // running at the stale rate for the rest of the session — onAudioReady()'s passthrough
    // write() always assumes numFrames is tagged at the *current* sampleRateHz_, so this is
    // exactly what turned into the reported "モニター音声がノイズだらけ→その後無音"
    // (garbled audio from the rate mismatch, then silence once the output stream's internal
    // buffer stayed permanently backed up). Closing both unconditionally here — whatever
    // rate this call ends up granting, neither stale stream may survive it.
    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }
    if (outputStream_) {
        outputStreamRaw_.store(nullptr, std::memory_order_release);
        outputStream_->requestStop();
        outputStream_->close();
        outputStream_.reset();
        monitoringEnabled_.store(false, std::memory_order_relaxed);
    }

    // Rate fallback ladder (docs/HIRES_AUDIO_DESIGN.md §3): descend from
    // requestedSampleRateHz through the standard rungs, skipping any rung above the
    // request. kStandardSampleRate is always included and tried last, so this always has
    // at least one candidate and (barring a device-level failure unrelated to rate) always
    // succeeds — same "never silently fail to record" guarantee as the SharingMode/
    // InputPreset ladder inside openInputStreamLocked.
    static constexpr int32_t kRateLadder[] = {192000, 96000, kStandardSampleRate};
    std::string lastError = "no candidate sample rate attempted";
    for (int32_t candidateRateHz : kRateLadder) {
        if (candidateRateHz > requestedSampleRateHz) {
            continue;
        }
        auto openResult = openInputStreamLocked(preferredInputDeviceId, candidateRateHz);
        if (openResult.isErr()) {
            lastError = openResult.error();
            continue;
        }

        if (candidateRateHz != requestedSampleRateHz) {
            AUCAMPRO_LOGW("Hi-res sample rate fallback: requested %d, granted %d",
                            requestedSampleRateHz, candidateRateHz);
        }
        inputStream_ = openResult.value();
        sampleRateHz_ = candidateRateHz;
        // Keeps the user's existing HighPass/EQ settings intact across the rate change —
        // see BiquadEq.h/HighPassFilter.h's setSampleRate() docs for why this recomputes
        // in place rather than needing these objects reconstructed.
        highPassFilter_.setSampleRate(sampleRateHz_);
        eq_.setSampleRate(sampleRateHz_);
        meter_.setSampleRate(sampleRateHz_);

        const oboe::Result startResult = inputStream_->requestStart();
        if (startResult != oboe::Result::OK) {
            inputStream_->close();
            inputStream_.reset();
            return Result<void, std::string>::Err(std::string("requestStart failed: ") +
                                                    oboe::convertToText(startResult));
        }
        return Result<void, std::string>::Ok();
    }

    return Result<void, std::string>::Err("All sample-rate fallback attempts failed: " + lastError);
}

void OboeFullDuplexEngine::stop() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    monitoringEnabled_.store(false, std::memory_order_relaxed);

    // Input stream MUST close first: Oboe guarantees onAudioReady() cannot fire again
    // once close() returns, which is what makes it safe to then close the output stream
    // below without racing the passthrough write() a still-running callback might
    // otherwise be issuing concurrently.
    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }

    if (outputStream_) {
        // Clear the audio-thread-visible pointer before actually closing — by this point
        // onAudioReady() can no longer be invoked at all (input stream already closed
        // above), so this ordering is a defensive belt-and-suspenders, not load-bearing.
        outputStreamRaw_.store(nullptr, std::memory_order_release);
        outputStream_->requestStop();
        outputStream_->close();
        outputStream_.reset();
    }
}

Result<void, std::string> OboeFullDuplexEngine::reopenInputStream(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(streamMutex_);

    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }

    // Device hot-swap only — keeps the engine's current sampleRateHz_ (set by [start]),
    // never changes rate here. A rate change is a distinct, explicit user action (Settings
    // quality picker) handled entirely by re-calling [start], not by this method.
    auto openResult = openInputStreamLocked(deviceId, sampleRateHz_);
    if (openResult.isErr()) {
        return Result<void, std::string>::Err(openResult.error());
    }
    inputStream_ = openResult.value();

    const oboe::Result startResult = inputStream_->requestStart();
    if (startResult != oboe::Result::OK) {
        inputStream_->close();
        inputStream_.reset();
        return Result<void, std::string>::Err(std::string("requestStart failed: ") +
                                                oboe::convertToText(startResult));
    }
    return Result<void, std::string>::Ok();
}

void OboeFullDuplexEngine::insertSilence(int32_t frameCount) {
    if (frameCount <= 0) {
        return;
    }
    // Non-RT context (called from Kotlin's device-reconnect orchestration), so a
    // heap-allocated scratch buffer is fine here even though it would not be inside
    // onAudioReady().
    std::vector<float> silence(static_cast<size_t>(frameCount) * kChannelCount, 0.0f);
    const size_t written = ringBuffer_.write(silence.data(), silence.size());
    if (written < silence.size()) {
        ringBufferOverrunCount_.fetch_add(1, std::memory_order_relaxed);
    }
}

Result<void, std::string> OboeFullDuplexEngine::setMonitoringEnabled(bool enabled, int32_t outputDeviceId) {
    std::lock_guard<std::mutex> lock(streamMutex_);

    if (!enabled) {
        // Deliberately does NOT close the output stream — see the header comment on this
        // method for why (the input callback may still be mid-write() on it).
        monitoringEnabled_.store(false, std::memory_order_relaxed);
        return Result<void, std::string>::Ok();
    }

    if (outputStream_) {
        // Already open (e.g. re-enabling); nothing further to do. Note: does not support
        // switching to a different outputDeviceId once open — see header comment.
        monitoringEnabled_.store(true, std::memory_order_relaxed);
        return Result<void, std::string>::Ok();
    }

    // sampleRateHz_ (the engine's current *input* rate), not a fixed constant — a
    // real-device-relevant fix (docs/HIRES_AUDIO_DESIGN.md §4/§6.5): onAudioReady() below
    // writes each callback's numFrames straight through to this output stream, so if the
    // engine is running hi-res (e.g. 96kHz) while this stream opened at a stale 48kHz, the
    // monitor passthrough would play back at roughly 2x speed/pitch.
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setSampleRate(sampleRateHz_)
        ->setChannelCount(kChannelCount)
        ->setUsage(oboe::Usage::Media)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
    if (outputDeviceId != oboe::kUnspecified) {
        builder.setDeviceId(outputDeviceId);
    }

    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result openResult = builder.openStream(stream);
    if (openResult != oboe::Result::OK) {
        return Result<void, std::string>::Err(std::string("monitor output openStream failed: ") +
                                                oboe::convertToText(openResult));
    }

    // 実機で発見 (2026-07-18): unlike openInputStreamLocked, this never verified what
    // Oboe/AAudio actually granted vs. what was requested (sampleRateHz_) — the same
    // "the OS may silently grant a different config even on success" risk this header's
    // own doc already calls out for a hi-res rate ("if this stream opened at a stale
    // 48kHz [...] monitor passthrough would play back at roughly 2x speed/pitch"), just
    // never actually guarded against. onAudioReady()'s passthrough write() below always
    // assumes the output stream's rate equals sampleRateHz_ (it just forwards each
    // callback's numFrames as-is) — a silent mismatch is exactly what turns into the
    // reported "モニター音声がノイズだらけ" at hi-res rates. Fail loudly instead, same as
    // the input path: [RecordingPipeline.setMonitoringEnabled] already treats a non-null
    // error here as "leave monitoring off and snap the UI toggle back."
    if (stream->getChannelCount() != kChannelCount || stream->getSampleRate() != sampleRateHz_) {
        AUCAMPRO_LOGE("Monitor output stream opened with unexpected config: channels=%d rate=%d (expected %d/%d)",
                        stream->getChannelCount(), stream->getSampleRate(), kChannelCount, sampleRateHz_);
        stream->close();
        return Result<void, std::string>::Err(
            "Monitor output stream channel/sample-rate did not match the input engine's current rate");
    }

    const oboe::Result startResult = stream->requestStart();
    if (startResult != oboe::Result::OK) {
        stream->close();
        return Result<void, std::string>::Err(std::string("monitor output requestStart failed: ") +
                                                oboe::convertToText(startResult));
    }

    // Diagnostic (2026-07-18, monitor-noise investigation): the success path used to log
    // nothing at all, making "monitoring is silently on and working" indistinguishable in
    // logcat from "the toggle never actually reached this code." Cheap and permanent, same
    // spirit as openInputStreamLocked's analogous AUCAMPRO_LOGI.
    AUCAMPRO_LOGI("Monitor output stream opened: deviceId=%d channelCount=%d sampleRate=%d",
                    stream->getDeviceId(), stream->getChannelCount(), stream->getSampleRate());

    outputStream_ = stream;
    // Published last, after the shared_ptr member and the stream is fully started, so
    // the audio thread never observes a non-null raw pointer to a stream that isn't
    // ready to accept write() calls yet.
    outputStreamRaw_.store(stream.get(), std::memory_order_release);
    monitoringEnabled_.store(true, std::memory_order_relaxed);
    return Result<void, std::string>::Ok();
}

void OboeFullDuplexEngine::setEqBandParams(int band, float freqHz, float q, float gainDb) {
    eq_.setBandParams(band, freqHz, q, gainDb);
}

int32_t OboeFullDuplexEngine::hardwareXRunCount() const {
    if (!inputStream_) {
        return 0;
    }
    oboe::ResultWithValue<int32_t> result = inputStream_->getXRunCount();
    return result ? result.value() : 0;
}

bool OboeFullDuplexEngine::getInputTimestamp(int64_t *outFramePosition, int64_t *outTimeNanos) const {
    if (!inputStream_) {
        return false;
    }
    oboe::ResultWithValue<oboe::FrameTimestamp> result = inputStream_->getTimestamp(CLOCK_MONOTONIC);
    if (!result) {
        return false;
    }
    *outFramePosition = result.value().position;
    *outTimeNanos = result.value().timestamp;
    return true;
}

size_t OboeFullDuplexEngine::drainEncoderBuffer(float *dst, size_t maxFrames) {
    return ringBuffer_.read(dst, maxFrames * kChannelCount) / kChannelCount;
}

void OboeFullDuplexEngine::flushRingBuffer() { ringBuffer_.clear(); }

oboe::DataCallbackResult OboeFullDuplexEngine::onAudioReady(oboe::AudioStream * /*stream*/, void *audioData,
                                                              int32_t numFrames) {
    auto *samples = static_cast<float *>(audioData);
    const size_t sampleCount = static_cast<size_t>(numFrames) * kChannelCount;

    inputGain_.process(samples, sampleCount);
    highPassFilter_.process(samples, static_cast<size_t>(numFrames));
    eq_.process(samples, static_cast<size_t>(numFrames));
    makeupGain_.process(samples, sampleCount);
    limiter_.process(samples, sampleCount);
    meter_.process(samples, static_cast<size_t>(numFrames), kChannelCount);

    const size_t written = ringBuffer_.write(samples, sampleCount);
    if (written < sampleCount) {
        // Encoder thread is falling behind the mic; this is the same kind of
        // backpressure condition §4.4 asks us to surface for the Muxer queue, applied
        // here to the audio ring buffer. RT-safe: only an atomic increment.
        ringBufferOverrunCount_.fetch_add(1, std::memory_order_relaxed);
    }

    if (monitoringEnabled_.load(std::memory_order_relaxed)) {
        oboe::AudioStream *output = outputStreamRaw_.load(std::memory_order_acquire);
        if (output != nullptr) {
            // timeoutNanoseconds=0: never blocks, per §4.2's prohibition on blocking I/O
            // in the audio callback. A short write is simply dropped (monitoring is
            // best-effort; it must never be allowed to threaten the input callback's
            // deadline or the encoder ring buffer's integrity).
            output->write(samples, numFrames, 0);
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void OboeFullDuplexEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    // Runs on an Oboe-internal thread, not the audio callback thread itself, but still
    // treated as non-blocking/log-only here; the actual "insert silence and reopen on a
    // new device" orchestration happens in Kotlin's AudioDeviceRouter (§4.2), which
    // observes the disconnect via AudioManager.registerAudioDeviceCallback and drives
    // reopenInputStream()/insertSilence() from a normal coroutine context.
    AUCAMPRO_LOGW("Stream closed after error: %s (direction=%d)", oboe::convertToText(error),
                    static_cast<int>(stream->getDirection()));
}

}  // namespace aucampro
