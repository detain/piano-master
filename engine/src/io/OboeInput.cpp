#include "OboeInput.h"

#if defined(__ANDROID__)

#include <android/log.h>

#include <algorithm>
#include <utility>

namespace engine::io {

namespace {

constexpr const char* kLogTag = "KeyQuestEngine";

// 32768 float frames = ~0.68 s at 48 kHz: far more headroom than the
// detector's 50% overlap needs, so the callback never has to drop during a
// soak. The detector task (P0.2.2) can shrink this once latency is measured.
constexpr std::size_t kDefaultRingCapacity = 1u << 15;

std::size_t effectiveRingCapacity(std::size_t requested) {
    return requested == 0 ? kDefaultRingCapacity : requested;
}

}  // namespace

OboeInput::OboeInput() : OboeInput(Config{}) {}

OboeInput::OboeInput(Config config)
    : config_(config),
      stream_(nullptr),
      ring_(effectiveRingCapacity(config.ringCapacityPowerOfTwo)),
      callback_(&ring_),
      active_(false) {
    // RingBuffer's constructor fails fast on a non-power-of-two capacity.
}

OboeInput::~OboeInput() {
    // Stop and close the stream BEFORE any member is destroyed: the Oboe
    // callback may still be running and references the ring buffer, which is
    // destroyed after this destructor body returns. Closing here guarantees
    // the callback has been torn down before the ring dies.
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

bool OboeInput::open() {
    // Early exit: already open (idempotent success).
    if (stream_) {
        return true;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setChannelCount(oboe::ChannelCount::Mono);
    if (config_.sampleRate > 0) {
        // Specifying a default sample rate avoids resampling latency on
        // API 26/27 (Oboe FAQ); the granted rate is logged below.
        builder.setSampleRate(config_.sampleRate);
    }
    builder.setInputPreset(oboe::InputPreset::Unprocessed);
    builder.setFormat(oboe::AudioFormat::Float);
    // API 26/27 input streams do not natively support Float; without this
    // Oboe refuses to open, with it Oboe converts I16 -> Float for us so the
    // callback always sees float frames.
    builder.setFormatConversionAllowed(true);
    builder.setDataCallback(&callback_);
    if (config_.bufferSizeHint > 0) {
        builder.setBufferCapacityInFrames(config_.bufferSizeHint);
    }

    const oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "OboeInput open failed: %s",
                            oboe::convertToText(result));
        return false;
    }

    // The granted buffer size is only adjustable after open (and not at all
    // on OpenSL ES input streams); treat the request as a hint.
    if (config_.bufferSizeHint > 0) {
        const oboe::ResultWithValue<int32_t> setResult =
            stream_->setBufferSizeInFrames(config_.bufferSizeHint);
        if (!setResult) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "OboeInput buffer size hint %d not granted: %s",
                                config_.bufferSizeHint,
                                oboe::convertToText(setResult.error()));
        }
    }

    // P0.2.1 deliverable: log the ACTUALLY-GRANTED stream properties. Android
    // silently downgrades requested properties (exclusive -> shared, 48 kHz ->
    // device-native, mono -> stereo on some devices); the device x
    // granted-mode table is built from these lines on the 5 test phones.
    __android_log_print(
        ANDROID_LOG_INFO, kLogTag,
        "OboeInput opened: deviceId=%d sampleRate=%d framesPerBurst=%d "
        "channelCount=%d bufferSizeInFrames=%d performanceMode=%s "
        "sharingMode=%s format=%s",
        stream_->getDeviceId(), stream_->getSampleRate(),
        stream_->getFramesPerBurst(), stream_->getChannelCount(),
        stream_->getBufferSizeInFrames(),
        oboe::convertToText(stream_->getPerformanceMode()),
        oboe::convertToText(stream_->getSharingMode()),
        oboe::convertToText(stream_->getFormat()));
    return true;
}

bool OboeInput::start() {
    // Early exit: already started (idempotent success).
    if (active_.load(std::memory_order_relaxed)) {
        return true;
    }
    // Fail fast: start() before open() is a caller bug.
    if (!stream_) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "OboeInput start() before open()");
        return false;
    }

    const oboe::Result result = stream_->start();
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "OboeInput start failed: %s",
                            oboe::convertToText(result));
        return false;
    }
    active_.store(true, std::memory_order_relaxed);
    return true;
}

bool OboeInput::stop() {
    // Early exit: nothing to stop (idempotent success).
    if (!active_.load(std::memory_order_relaxed)) {
        return true;
    }
    active_.store(false, std::memory_order_relaxed);

    if (!stream_) {
        return true;
    }
    const oboe::Result result = stream_->stop();
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "OboeInput stop returned: %s",
                            oboe::convertToText(result));
        // A failed stop is not fatal: active_ is already cleared, and the
        // destructor will stop the stream again during teardown.
    }
    return true;
}

bool OboeInput::isActive() const {
    return active_.load(std::memory_order_relaxed);
}

std::size_t OboeInput::read(float* out, std::size_t maxFrames) {
    // Early exits: nothing to read into, or nothing available.
    if (out == nullptr || maxFrames == 0) {
        return 0;
    }
    const std::size_t available = ring_.available();
    if (available == 0) {
        return 0;
    }

    // Pop only what is there: the producer can only add, never remove, so a
    // pop of toRead <= available cannot fail.
    const std::size_t toRead = std::min(maxFrames, available);
    ring_.pop(out, toRead);
    return toRead;
}

std::size_t OboeInput::available() const {
    return ring_.available();
}

}  // namespace engine::io

#else  // !defined(__ANDROID__)

// Fail fast: OboeInput is Android-only glue. The host engine_core build must
// not compile this file (engine/CMakeLists.txt excludes it); if it does, the
// platform-portable engine would gain a hidden Android dependency.
#error "OboeInput.cpp is Android-only; only the Android NDK build may compile it"

#endif  // defined(__ANDROID__)