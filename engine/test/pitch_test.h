#pragma once

// Runs the deterministic A0..C8 pitch suite against generated WAV fixtures
// (see test/tools/gen_pitch_fixtures.py). Covers every white and black key
// MIDI 21..108: lowFreq (4096-window) for MIDI 21..42, standard 2048-window
// for MIDI 43..108. NOT part of libengine_core.
//
// Returns true when the suite passes: >=87/88 notes correct with zero
// confident octave errors (a confident octave error is worse than a miss for
// this detector), every boundary-honesty negative holds (standard 2048 must
// not confidently claim A0..E1, MIDI 21..28, whose periods exceed the
// 1023-sample search boundary), the standard-mode floor pin holds
// (G#1..F#2, MIDI 32..42, must resolve correctly — locks in the ~52 Hz
// reliable floor), and the C8 top-edge check resolves. MIDI 29..31
// (F1..G1) are informational probes only: F1/F#1 sit just beyond the
// boundary and are recovered by interpolation extrapolation, and G1's low
// edge is ragged (it can read one semitone flat).
bool runPitchTests(const char* fixturesDir);
