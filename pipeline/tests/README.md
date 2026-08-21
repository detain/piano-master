# Pipeline tests

Layered strategy from plan §8.2.13, run with `pytest`. Budget: under 5
minutes; a required CI job. Any content bug found in review becomes a fixture
in the same PR that fixes it.

## Layers

1. **Golden fixtures** — the §20 P1.1.3 awkward-case set (pickup bars,
   mid-song key change, triplets, ties across chunk boundaries, 6/8, repeat
   structures) built on every commit and compared byte-for-byte against
   committed outputs.

2. **Bad-input corpus** — one file per defect class in `tests/bad/`, each
   asserting a specific, actionable error message. Growing this corpus is how
   the validate stage matures; no stack traces are allowed in the messages.

3. **Property tests** (hypothesis) — repeat expansion round-trips the
   `repeatMap`; tuplet durations sum exactly; tie resolution; hand-assignment
   invariants (no note unassigned, no impossible span).

4. **Round-trip** — SongPack → MIDI → compare against the normalized source.
   Catches whole classes of silent data loss.

5. **Audio checks as assertions** — loudness (−16 LUFS / −1 dBTP), stem
   alignment (decoded onset drift ≤ 10 ms vs MIDI), mic-safe spectrum (no
   energy in the piano fundamental range). Measurements, not eyeballs.

6. **Determinism check** — build every golden fixture twice on different
   machines and byte-compare (§8.2.10). A failure means real nondeterminism
   crept in — stop and fix it before `pipeline diff` starts lying.