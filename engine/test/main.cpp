#include "engine/dsp/YinDetector.h"
#include "engine/engine.h"
#include "note_queue_test.h"
#include "pitch_test.h"
#include "ring_test.h"

#include <cassert>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <numbers>
#include <vector>

// Fail fast: the pitch suite is only meaningful with generated fixtures, so
// the build must always define where they live (CMake wires
// ${CMAKE_BINARY_DIR}/fixtures).
#ifndef PITCH_FIXTURES_DIR
#error "PITCH_FIXTURES_DIR must be defined (CMake target_compile_definitions)"
#endif

namespace {

// One-shot smoke check for the YIN detector: a clean full-scale 440 Hz sine
// must resolve to MIDI 69 with high confidence. The full pitch test suite
// lands in a follow-up task.
bool runYinSmokeCheck() {
    constexpr std::size_t kSampleRate = 48000;
    constexpr std::size_t kWindowSize = 2048;
    constexpr double kFreqHz = 440.0;

    std::vector<float> sine(kWindowSize);
    for (std::size_t i = 0; i < kWindowSize; ++i) {
        const double phase =
            2.0 * std::numbers::pi_v<double> * kFreqHz *
            static_cast<double>(i) / static_cast<double>(kSampleRate);
        sine[i] = static_cast<float>(std::sin(phase));
    }

    const engine::dsp::PitchResult result =
        engine::dsp::yinDetect(sine.data(), kWindowSize, kSampleRate);
    if (result.pitch != 69 || result.confidence <= 0.8) {
        std::fprintf(stderr,
                     "yinDetect smoke: expected pitch 69 confidence > 0.8, "
                     "got pitch %d freq %.2f Hz confidence %.3f\n",
                     result.pitch, result.freqHz, result.confidence);
        return false;
    }
    return true;
}

}  // namespace

int main() {
    if (!runRingBufferTests()) {
        return 1;
    }
    if (!runNoteQueueTests()) {
        return 1;
    }
    if (!runYinSmokeCheck()) {
        return 1;
    }
    if (!runPitchTests(PITCH_FIXTURES_DIR)) {
        return 1;
    }

    const int current = engine::version();
    assert(current == 1 && "engine::version() must start at 1");
    // Keep the check meaningful even when NDEBUG strips the assert.
    return current == 1 ? 0 : 1;
}
