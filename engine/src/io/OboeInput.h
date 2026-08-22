#pragma once

#include <cstddef>
#include <cstdint>

#if defined(__ANDROID__)

#include <atomic>
#include <memory>

#include <oboe/Oboe.h>

#include "engine/dsp/RingBuffer.h"

namespace engine::io {

// RAII wrapper around an Oboe input (microphone) stream.
//
// Ownership: construct with a Config, then open() -> start() to begin
// capture. The destructor stops and closes the stream, so members are never
// touched after the Oboe callback has been torn down.
//
// Threading contract:
// - The Oboe audio callback (real-time thread) does NOTHING but push the
//   delivered frames into a lock-free SPSC ring buffer: no allocation, no
//   locks, no JNI (plan §20 P0.2.3).
// - The consumer (e.g. the JNI drain on a Kotlin coroutine) reads frames
//   with read(). Exactly one consumer thread.
// - open()/start()/stop() are lifecycle calls and must not race each other.
//
// Android silently downgrades requested properties (exclusive -> shared
// mode, 48 kHz -> device-native rate, requested channels, etc.); the granted
// values are logged on open (plan §20 P0.2.1 deliverable: the device x
// granted-mode table needs the 5 test phones, this log line is the hook).
class OboeInput {
public:
    struct Config {
        // Requested sample rate in Hz. 48000 is the near-universal native
        // rate; Oboe falls back to the device's own rate when unsupported.
        // 0 = leave unspecified and let Oboe pick the native rate.
        int32_t sampleRate = 48000;
        // Requested buffer size in frames (0 = Oboe default). Only a hint:
        // input streams on OpenSL ES cannot change it after open.
        int32_t bufferSizeHint = 0;
        // Ring buffer capacity (power of two; 0 = default 32768) for the
        // audio callback's write side. Doubles as the drop-vs-block budget:
        // the callback drops frames rather than ever blocking.
        std::size_t ringCapacityPowerOfTwo = 0;
    };

    explicit OboeInput();  // default config
    explicit OboeInput(Config config);
    ~OboeInput();

    OboeInput(const OboeInput&) = delete;
    OboeInput& operator=(const OboeInput&) = delete;

    // Creates and opens the stream, then logs the actually-granted
    // properties. Returns false (and logs) on failure. Idempotent: calling
    // again after success is a no-op returning true.
    bool open();

    // Starts capture. Returns false (and logs) on failure.
    bool start();

    // Stops capture. Safe to call when not started; returns true.
    bool stop();

    // True while the stream is open and started.
    bool isActive() const;

    // Consumer side: copies up to maxFrames available frames into out and
    // returns the count copied. Never blocks.
    std::size_t read(float* out, std::size_t maxFrames);

    // Number of frames currently available to read (a hint; may be stale).
    std::size_t available() const;

private:
    // The audio callback. Deliberately tiny: writing frames into the ring
    // buffer is the only thing that may happen on the real-time thread.
    class Callback final : public oboe::AudioStreamDataCallback {
    public:
        explicit Callback(engine::dsp::RingBuffer* ring) : ring_(ring) {}

        oboe::DataCallbackResult onAudioReady(oboe::AudioStream* /*stream*/,
                                              void* audioData,
                                              int32_t numFrames) override {
            const float* frames = static_cast<const float*>(audioData);
            if (!ring_->push(frames, static_cast<std::size_t>(numFrames))) {
                // Ring full: drop rather than block. The counter feeds the
                // P0.2.3 soak expectation (zero dropped events / glitches).
                droppedFrames_.fetch_add(static_cast<std::uint64_t>(numFrames),
                                         std::memory_order_relaxed);
            }
            return oboe::DataCallbackResult::Continue;
        }

        std::uint64_t droppedFrames() const {
            return droppedFrames_.load(std::memory_order_relaxed);
        }

    private:
        engine::dsp::RingBuffer* ring_;
        std::atomic<std::uint64_t> droppedFrames_{0};
    };

    Config config_;
    std::shared_ptr<oboe::AudioStream> stream_;
    engine::dsp::RingBuffer ring_;
    Callback callback_;
    std::atomic<bool> active_{false};
};

}  // namespace engine::io

#else  // !defined(__ANDROID__)

// Host (non-Android) stub. The host engine_core build excludes OboeInput.cpp
// (see engine/CMakeLists.txt), but a host translation unit that includes this
// header must still compile. Every member is a fail-fast no-op: the class is
// Android glue, not part of the platform-portable engine.
namespace engine::io {

class OboeInput {
public:
    struct Config {
        int32_t sampleRate = 48000;
        int32_t bufferSizeHint = 0;
        std::size_t ringCapacityPowerOfTwo = 0;
    };

    OboeInput() = default;
    explicit OboeInput(Config) {}
    ~OboeInput() = default;
    OboeInput(const OboeInput&) = delete;
    OboeInput& operator=(const OboeInput&) = delete;

    bool open() { return false; }
    bool start() { return false; }
    bool stop() { return true; }
    bool isActive() const { return false; }
    std::size_t read(float*, std::size_t) { return 0; }
    std::size_t available() const { return 0; }
};

}  // namespace engine::io

#endif  // defined(__ANDROID__)