#pragma once

// Runs the deterministic A0..C8 pitch suite against generated WAV fixtures
// (see test/tools/gen_pitch_fixtures.py). Covers every white and black key
// MIDI 21..108: lowFreq (4096-window) for MIDI 21..42, standard 2048-window
// for MIDI 43..108. NOT part of libengine_core.
//
// Returns true when the suite passes: >=87/88 notes correct, every
// boundary-honesty negative holds (standard 2048 must not confidently claim
// A0..E1, MIDI 21..28, whose periods exceed the 1023-sample search
// boundary), and the C8 top-edge check resolves.
bool runPitchTests(const char* fixturesDir);