#pragma once

#include <cstdint>

namespace engine {

// Where a NoteEvent originated. Everything above the engine consumes one
// unified event type so mic, MIDI, and touch courses share a lesson player.
enum class NoteSource : uint8_t {
    MIC,
    MIDI,
    TOUCH,
};

// One detected or synthesized note, timestamped on the audio clock.
// Trivial and noexcept by design: events cross the JNI boundary as plain
// data and are copied freely between engine threads.
struct NoteEvent {
    int pitch;          // MIDI note number, 0-127
    int velocity;       // 0-127
    int64_t onTimeNs;   // audio-clock time the note started
    int64_t offTimeNs;  // audio-clock time the note ended, -1 while still on
    NoteSource source;

    friend bool operator==(const NoteEvent&, const NoteEvent&) = default;
};

}  // namespace engine