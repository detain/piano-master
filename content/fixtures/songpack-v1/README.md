# SongPack v1 — golden fixtures

Hand-authored packs that every SongPack consumer validates against the
canonical schema (`content/schema/songpack-v1.json`). Each fixture directory
contains the four JSON-format-contract documents (`manifest.json`,
`notes.json`, `chunks.json`, `skills.json`); audio/, cover.webp, LICENSE.txt
and checksums.json are contractually described but not produced for fixtures
(plan §8.1.2/§8.1.7/§8.1.8, spec docs/specs/songpack-v1.md).

All fixtures validate clean against the schema AND pass the Python semantic
checks (`pipeline/pipeline/songpack/validator.py`).

| Fixture | Awkward case(s) covered |
|---|---|
| `pickup_anacrusis/` | Pickup bars — `pickupBeats: 1.0`, correct `durationBeats`/tempoMap alignment (33 beats = 1-beat pickup + 8 × 4/4 measures) |
| `key_change_mid_song/` | Mid-song key change — C major (fifths 0) → G major (fifths 1) at bar 9 (`atBeat: 32`); F# notes carry display-only `accidental: "sharp"`; **two arrangement levels** to exercise per-level note sets |
| `triplets_6_8/` | 6/8 time signature + triplet figures. Beat = quarter note, so a 6/8 measure is 3 beats and `durBeats` 0.3333333333333333 = 1/3 (eighth-note triplet in 6/8) and 0.6666666666666666 = 2/3 (quarter-note triplet); exact rational-ish floats, do not assume exact float summation |
| `tie_across_chunks/` | Ties across a chunk boundary — the last note of chunk c01 (`G4` at beat 15, array index 19) has `tieToIndex: 20` pointing at the first note of chunk c02 (beat 16); also an internal tie (index 37 → 38). Proves tie references are index-based across the whole level array, not beat-based |
| `repeat_structure/` | Linearized repeat — A–B–A′ written out as linear notes, `loopSafe` chunks marking what can loop, plus the optional `repeatMap` manifest field; also carries forward-compat unknown keys (`futureField: true` in the manifest, `futureNoteField: 1` on a note) proving unknown keys are ignored (§8.1.9) |