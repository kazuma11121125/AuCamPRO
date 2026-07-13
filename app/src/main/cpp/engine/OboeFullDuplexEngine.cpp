#include "engine/OboeFullDuplexEngine.h"

#include <vector>

#include "common/Log.h"

namespace procamera {

OboeFullDuplexEngine::OboeFullDuplexEngine()
    : eq_(kSampleRate, kChannelCount),
      limiter_(-1.0f),
      meter_(kSampleRate),
      ringBuffer_(kRingBufferCapacityFrames * kChannelCount) {}

OboeFullDuplexEngine::~OboeFullDuplexEngine() { stop(); }

Result<std::shared_ptr<oboe::AudioStream>, std::string> OboeFullDuplexEngine::openInputStreamLocked(
    int32_t deviceId) {
    // Fallback ladder per §4.2: try the ideal config first, then relax SharingMode, then
    // relax InputPreset. Every downgrade is logged — never silent.
    struct Attempt {
        oboe::SharingMode sharingMode;
        oboe::InputPreset inputPreset;
        const char *description;
    };
    const Attempt attempts[] = {
        {oboe::SharingMode::Exclusive, oboe::InputPreset::Unprocessed, "Exclusive+Unprocessed"},
        {oboe::SharingMode::Shared, oboe::InputPreset::Unprocessed, "Shared+Unprocessed (SharingMode fallback)"},
        {oboe::SharingMode::Shared, oboe::InputPreset::VoiceRecognition,
         "Shared+VoiceRecognition (InputPreset fallback)"},
    };

    for (const Attempt &attempt : attempts) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(attempt.sharingMode)
            ->setFormat(oboe::AudioFormat::Float)
            ->setSampleRate(kSampleRate)
            ->setChannelCount(kChannelCount)
            ->setInputPreset(attempt.inputPreset)
            ->setFormatConversionAllowed(true)
            ->setChannelConversionAllowed(true)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (deviceId != oboe::kUnspecified) {
            builder.setDeviceId(deviceId);
        }

        std::shared_ptr<oboe::AudioStream> stream;
        const oboe::Result openResult = builder.openStream(stream);
        if (openResult != oboe::Result::OK) {
            PROCAMERA_LOGW("openInputStream attempt [%s] failed: %s", attempt.description,
                            oboe::convertToText(openResult));
            continue;
        }

        if (&attempt != &attempts[0]) {
            PROCAMERA_LOGW("Input stream opened via fallback: %s", attempt.description);
        }
        // The device may still silently grant a different config than requested even on
        // success (documented AAudio behavior) — surface that too rather than assume.
        if (stream->getSharingMode() != attempt.sharingMode) {
            PROCAMERA_LOGW("Input stream SharingMode downgraded by OS to %d",
                            static_cast<int>(stream->getSharingMode()));
        }
        if (stream->getInputPreset() != attempt.inputPreset) {
            PROCAMERA_LOGW("Input stream InputPreset downgraded by OS to %d",
                            static_cast<int>(stream->getInputPreset()));
        }

        // The entire DSP chain (EQ/limiter/meter) and the ring buffer's frame math
        // hard-assume kChannelCount/kSampleRate interleaved float frames. setFormat/
        // SampleRate/ChannelConversionAllowed(true) above ask Oboe to make that true
        // regardless of the device's native config, but this has not been verified on a
        // real device (§ "確信度の明示" — no hardware available in this environment), so
        // fail loudly here rather than silently misinterpret e.g. a mono stream as
        // interleaved stereo (which would corrupt every sample, not just degrade quality).
        if (stream->getChannelCount() != kChannelCount || stream->getSampleRate() != kSampleRate) {
            PROCAMERA_LOGE("Input stream opened with unexpected config: channels=%d rate=%d (expected %d/%d)",
                            stream->getChannelCount(), stream->getSampleRate(), kChannelCount, kSampleRate);
            stream->close();
            return Result<std::shared_ptr<oboe::AudioStream>, std::string>::Err(
                "Input stream channel/sample-rate conversion did not produce the expected format");
        }

        return Result<std::shared_ptr<oboe::AudioStream>, std::string>::Ok(stream);
    }

    return Result<std::shared_ptr<oboe::AudioStream>, std::string>::Err(
        "All input stream open attempts failed (Exclusive/Shared x Unprocessed/VoiceRecognition)");
}

Result<void, std::string> OboeFullDuplexEngine::start(int32_t preferredInputDeviceId) {
    std::lock_guard<std::mutex> lock(streamMutex_);

    auto openResult = openInputStreamLocked(preferredInputDeviceId);
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

    auto openResult = openInputStreamLocked(deviceId);
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

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setSampleRate(kSampleRate)
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
    const oboe::Result startResult = stream->requestStart();
    if (startResult != oboe::Result::OK) {
        stream->close();
        return Result<void, std::string>::Err(std::string("monitor output requestStart failed: ") +
                                                oboe::convertToText(startResult));
    }

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

size_t OboeFullDuplexEngine::drainEncoderBuffer(float *dst, size_t maxFrames) {
    return ringBuffer_.read(dst, maxFrames * kChannelCount) / kChannelCount;
}

oboe::DataCallbackResult OboeFullDuplexEngine::onAudioReady(oboe::AudioStream * /*stream*/, void *audioData,
                                                              int32_t numFrames) {
    auto *samples = static_cast<float *>(audioData);
    const size_t sampleCount = static_cast<size_t>(numFrames) * kChannelCount;

    eq_.process(samples, static_cast<size_t>(numFrames));
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
    PROCAMERA_LOGW("Stream closed after error: %s (direction=%d)", oboe::convertToText(error),
                    static_cast<int>(stream->getDirection()));
}

}  // namespace procamera
