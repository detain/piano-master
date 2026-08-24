# Pipeline v0 — contract (P1.2)

Status: **Done** (P1.2, plan §20 P1.2; stage design plan §8.2)
Implementations: `pipeline/pipeline/build/` (stage modules), `pipeline/pipeline/cli.py`
Tests: `pipeline/tests/test_{build_stages,repeat_expansion,bad_input,audio_stems}.py`

This document is the v0 pipeline contract: input → stage artifacts → pack,
the determinism guarantees, the renderer semantics, and what is deliberately
deferred. The SongPack contract it emits is `docs/specs/songpack-v1.md` +
`content/schema/songpack-v1.json` (frozen).

---

## 1. Input and output

```
pipeline ingest  score.musicxml --song-id <id> --source <provenance> [--edition ...]
pipeline build   <song-id> [--from-stage N] [--stage N] [--renderer sine|fluidsynth]
                  [--timestamp now] [--strict] [--tempo BPM] [--title ...] ...
pipeline audio   <song-id> [--renderer sine|fluidsynth]
pipeline validate <song-id> [--strict]
pipeline diff    <song-id> --against <other.pack|published>
pipeline publish <song-id> --env staging|prod
pipeline batch   --manifest FILE --parallel N
pipeline eval    ...   (P0.3.1, unchanged)
```

- **Input**: uncompressed MusicXML (`.musicxml`/`.xml`). `.mxl` and MIDI are
  rejected with named messages at ingest (v0 scope cut — §8.2.1 lists them,
  the v0 renderers normalize MusicXML only).
- **Ingest** copies the source byte-for-byte into `content/store/<song_id>/`
  (gitignored), hashed, with a mandatory `--source` provenance reference.
  Re-ingesting the same file + source is a no-op (same sha256).
- **Output**: `content/builds/<song_id>/packs/song_<id>_v<n>.pack` — a
  deterministic zip validated against the canonical SongPack schema before it
  exists. Stage intermediates live under `content/builds/<song_id>/stage-N/`
  so `--from-stage N` resumes from the stored intermediate of stage N−1.

Every stage is a pure function `(song doc, config) -> (song doc, report)`;
the runner owns all I/O. A failing stage prints one actionable error to
stderr and exits non-zero — never a Python stack trace (the bad-input corpus
in `pipeline/tests/bad/` asserts this).

## 2. The stages (v0 behavior)

| # | Stage | v0 behavior |
|---|-------|-------------|
| 1 | ingest | byte-for-byte copy + provenance.json; fails without `--source` |
| 2 | validate | music21 parse; 1–2 staves; ≤ 4 voices per staff; measures sum to time signature; A0–C8 (warn outside C2–C7); no simultaneous same-pitch same-voice; chord > 10th warns; tie targets exist; named rejection of D.S./D.C./Coda/Segno/Fine, glissando, cue notes, turns, zero durations; tempo mark required (or `--tempo`) |
| 3 | normalize | repeat/jump expansion via an explicit state machine (`build/repeats.py`); exact tuplet fractions; grace notes → real notes (scoringWeight 0) before the beat; trill/mordent/inverted-mordent → expansion notes (`isOrnamentExpansion`, scoringWeight 0.2); canonical voice renumbering; ties kept as paired records; rests/pedal/chord-symbols/lyrics as metadata; repeatMap emitted |
| 4 | hands | staff → hand primary signal + pitch-based crossing correction; per-note confidence (low confidence surfaced); single-staff splits at MIDI 60; **fingering NOT generated (P2)** |
| 5 | chunking | 2–8 bar **suggestions** at phrase boundaries (rest/cadence/repeat signals); never splits a tie, never starts mid-beat; `loopSafe` where a loop restarts musically; never auto-published |
| 6 | difficulty | difficulty 1 everywhere (calibrated scoring is P2); empty skill requirements |
| 7 | layout | beamGroup (rhythm-derived), xHint (duration spacing), lane (hand+voice), staff; per-chunk `viewport` hints (pitch range + keyboard zone) on the chunk records |
| 8 | levels | single level `"1"` (Essentials); L2/L3 generation is later |
| 9 | audio | sine renderer (default) or fluidsynth; loudnorm −16 LUFS/−1 dBTP; Opus; measured loudness/mic-safe/alignment checks fail the build on violation |
| 10 | pack | deterministic zip: sorted keys, fixed floats, zeroed timestamps; checksums.json; schema-validated; buildInfo excluded from content hash |
| 11 | publish | pre-publish gate (schema + provenance + audio + size) → filesystem catalog + pointer flip; rollback = pointer flip |

### Repeat expansion (supported set)

The state machine supports **simple repeats** (`|: … :|`), **first/second
endings** (volta brackets with numeric endings), and **nested repeats** (the
open-bracket stack is hierarchical). **D.S./D.C./Coda/Segno/Fine are rejected
with a named error in stage 2** — never expanded in v0. The `repeatMap`
(manifest, optional) records which source measures produced which output
beats, labeled A/B/C by first appearance with `source: main` or
`repeat-of-<label>`.

### Ornament expansion (documented default)

- **Trill**: alternates the written pitch and its upper neighbor (+2
  semitones, chromatic — diatonic neighbor is a P2 refinement) in 16th-note
  subdivisions (0.25 beats), covering the note duration, minimum 2 notes,
  starting on the upper neighbor.
- **Mordent**: `main → lower (-1 semitone) → main`; **inverted mordent**:
  `main → upper (+2) → main`. Three notes splitting the duration.
- Every expansion note: `isOrnamentExpansion: true`, `scoringWeight: 0.2`.
- **Turns, trill extensions (wavy-line), and anything exotic are rejected by
  name in stage 2.**

### Grace notes

Real notes with `scoringWeight: 0` placed just before the beat they ornament:
`startBeat = mainNoteStart − 0.125 × (distance from the main note)`, clamped
at 0 (beat 0 is the first pickup note; a grace before it lands on 0).

## 3. Determinism (plan §8.2.10)

Byte-identical output for identical input is the contract — content diffs mean
something and CDN caching is safe. Enforced by construction:

1. **JSON**: sorted keys, floats rounded to 12 decimals, `-0.0` normalized,
   compact separators, trailing newline (`build/determinism.py`).
2. **Zip**: entries sorted, timestamps zeroed to `1980-01-01`, fixed mode
   (0o644), fixed deflate level.
3. **Audio**: numpy synthesis is deterministic; ffmpeg loudnorm two-pass is
   deterministic; the Ogg/Opus muxer embeds a random serial number per
   process, so `canonicalize_ogg` rewrites every page to a fixed serial and
   recomputes the page CRCs (verified lossless — decoded PCM is identical).
4. **`buildInfo.buildTimestamp`**: `SOURCE_DATE_EPOCH` if set, else the fixed
   sentinel `1970-01-01T00:00:00Z`. `--timestamp now` is the opt-in wall-clock
   escape hatch for publishing.
5. **`buildInfo` is EXCLUDED from the content hash**, so the cache key never
   moves with a timestamp.

The CI determinism step builds the golden fixture set twice into separate
stores and `cmp`s the packs (`pipeline/scripts/build_golden.py`).

## 4. Renderer semantics (stage 9)

- **`sine` (v0 default)** — deterministic numpy synthesis, no new pip deps:
  RH and LH stems from the hand field, full mix, a mic-safe pad (3.2 kHz sine
  whose amplitude follows note density — no energy in 80 Hz–2 kHz), and a
  count-in click at the default tempo. Then, per stem: ffmpeg two-pass
  loudnorm to −16 LUFS / −1 dBTP, Opus encode (48 kHz; stereo 96 kbps full
  mixes, mono 64 kbps mic-safe), Ogg canonicalization, and three measured
  checks that **fail the build**:
  - encoded loudness within ±1.5 LUFS of −16 (encoding shifts it);
  - mic-safe spectrum empty in 80 Hz–2 kHz (decoded);
  - decoded full-stem onsets within 10 ms of the note timeline (first onset
    mandatory; later onsets checked only across real silence gaps — the
    detector ignores back-to-back transitions).
- **`fluidsynth` (code-complete, CI-independent)** — subprocess-based (no new
  pip deps), activated by `--renderer fluidsynth` only when the binary is on
  PATH; requires `--soundfont <path>` whose sha256 matches
  `--soundfont-sha256`. It builds per-stem MIDI (RH channel 0, LH channel 1)
  with mido and renders via `fluidsynth -F`. Cannot be exercised on this
  server (no sudo) and CI must not depend on it; when absent it fails with an
  actionable message.

`audioProfile` in the manifest records the renderer, loudness target, codec,
sample rate, and bitrates. A `--no-audio` build emits a JSON-only pack (with
the default sine audioProfile) that is not publishable.

## 5. v0 scope cuts vs plan §8.2

Deferred to P2+ and documented, not silently dropped:

- calibrated difficulty scoring and skill inference (stage 6 = 1 + empty skills);
- L2/L3 automated arrangement reductions (stage 8 = single Essentials level);
- fingering generation (stage 4 = hands only);
- D.S./D.C./Coda repeat expansion (rejected with a named error);
- diatonic ornament neighbors (chromatic neighbors used);
- the DGX-520 hardware renderer (sine is the CI/default backend);
- real CDN/CMS publish orchestration (stage 11 = filesystem catalog + pointer
  flip); job outbox/queue-consumer integration (§8.2.12);
- `.mxl` and MIDI ingest.

The bad-input corpus (`pipeline/tests/bad/`, one file per defect class) is the
executable contract that a defect produces a specific, named, actionable
message — every content bug found downstream gets a new fixture in the same PR
(plan §8.2.13).