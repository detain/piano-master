# KeyQuest Pipeline

Offline Python content workers that turn MusicXML source scores into SongPack
(the app-native format) — plan §8.2. Runs standalone from the CLI and doubles
as the CMS's backend: the admin API enqueues a `job_outbox` row, a Webman
`queue-consumer` invokes the worker, and the CMS polls stage-level progress
(§8.2.12).

## Purpose

Eleven stages: ingest → validate → normalize → hands+fingering → chunking →
difficulty → layout → levels → audio → pack → publish. Each stage is a pure
function `(input artifact, config) → (output artifact, report)`, runnable
standalone; stage artifacts are stored under `content/builds/<song_id>/stage-N/`
so `--from-stage` re-runs never repeat an expensive render. The v0 contract is
documented in `docs/specs/pipeline-v0.md`.

## CLI surface (P1.2, plan §8.2)

```bash
pipeline ingest   score.musicxml --song-id fur-elise --source imslp:12345
pipeline build    fur-elise [--from-stage N] [--stage N] [--level 1,2,3]
                  [--renderer sine|fluidsynth] [--timestamp now] [--strict]
                  [--tempo BPM] [--title ...] [--composer ...]
pipeline audio    fur-elise --renderer sine|fluidsynth
pipeline validate fur-elise --strict
pipeline diff     fur-elise --against other.pack|published
pipeline publish  fur-elise --env staging|prod
pipeline batch    --manifest weekly-batch.yaml --parallel 4
pipeline eval     audio.wav ground_truth.mid   # P0.3.1, unchanged
```

`build` requires the song to have been ingested first; `--from-stage N`
resumes from the stored intermediate of stage N−1. Stage failures print one
actionable error to stderr and exit non-zero — no Python stack traces.

Stages in v0:

1. **ingest** — copy the source byte-for-byte into the content store + write
   provenance; fails without `--source` (§8.2.1).
2. **validate** — music21 structural/range/musical-sanity checks and the
   named unsupported-construct enumeration (§8.2.2).
3. **normalize** — repeat/jump expansion via an explicit state machine,
   tuplets, grace/ornament expansion, canonical voices, ties, repeatMap
   (§8.2.3).
4. **hands** — staff→hand with crossing correction + per-note confidence
   (fingering is P2).
5. **chunking** — 2–8 bar phrase suggestions with rationale; never splits a
   tie; never auto-published (§8.2.5).
6. **difficulty** — v0 emits difficulty 1 (calibrated scoring is P2).
7. **layout** — beamGroup/xHint/lane precomputed + per-chunk viewport hints
   (§8.2.7).
8. **levels** — single level "1" (Essentials); L2/L3 generation is later.
9. **audio** — sine renderer (default) or fluidsynth; −16 LUFS/−1 dBTP Opus
   stems; measured loudness/mic-safe/alignment checks (§8.2.9).
10. **pack** — deterministic zip (sorted keys, fixed floats, zeroed stamps),
    validated against the canonical schema before it exists (§8.2.10).
11. **publish** — pre-publish gate → filesystem catalog + pointer flip;
    rollback is a pointer flip (§8.2.11, simplified v0).

## Determinism (§8.2.10)

Output must be byte-identical for identical input, so content diffs mean
something and CDN caching is safe. Required everywhere: sorted JSON keys,
fixed float formatting (12 decimals), zip entries in sorted order with
timestamps zeroed, pinned rendering tools, and `buildInfo` (timestamp)
excluded from the content hash. `buildInfo.buildTimestamp` = `SOURCE_DATE_EPOCH`
if set, else the fixed sentinel `1970-01-01T00:00:00Z`; `--timestamp now`
opts into wall-clock time for publishing. The Ogg/Opus muxer's random serial
number is canonicalized post-encode (lossless, verified). A CI job
(`engine-host-tests` → Determinism step) builds every golden fixture twice and
byte-compares the packs.

## Renderer backends (stage 9)

- **sine** (v0 default, CI-safe): deterministic numpy synthesis; needs only
  the existing pip deps + ffmpeg (preinstalled on CI).
- **fluidsynth** (code-complete, not exercised here — no sudo): subprocess +
  `mido`, requires `--soundfont <path>` whose sha256 matches
  `--soundfont-sha256`. CI must not depend on it.

## v0 scope cuts vs plan §8.2

Calibrated difficulty + skill inference (P2), L2/L3 generation (later),
fingering (P2), D.S./D.C./Coda expansion (rejected with a named error),
diatonic ornament neighbors (chromatic used), the DGX renderer, real
CDN/CMS publish orchestration (v0 = filesystem catalog), `.mxl`/MIDI ingest.
Full details in `docs/specs/pipeline-v0.md`.

## Bad-input corpus

`pipeline/tests/bad/` holds one small MusicXML per defect class from §8.2.2
(malformed XML, zero-duration, measure-sum mismatch, no tempo, out-of-range
pitch, D.S. al Coda, same-pitch same-voice, voice overflow, glissando, cue
notes, turn ornaments) plus the warning-only chord-span case. Each must
produce a specific, actionable message with no stack trace. Every content bug
found downstream becomes a fixture in the same PR.

## Evaluation harness (plan §20 P0.3.1)

Offline evaluation for pitch-tracking / transcription models: takes
(audio file, aligned ground-truth MIDI) pairs plus any model wrapper and emits
note-level precision/recall/F1 (mir_eval conventions), the onset timing error
distribution, chord recall by chord size, and the octave-error rate.

```bash
# score the pyin floor (monophonic baseline, plan §5.3) on one pair
pipeline eval audio.wav ground_truth.mid --wrapper pyin --json /tmp/out.json

# prove the plumbing: the ground-truth oracle must score perfectly
pipeline eval audio.wav ground_truth.mid --wrapper ground-truth
```

Tolerances: `--onset-tol 0.05` (seconds), `--offset-tol 0.2` (mir_eval's
`offset_ratio` — a *fraction of the reference note duration*, not seconds),
`--pitch-tol-cents 50.0` (mir_eval measures pitch distance in **cents**;
50 cents = 0.5 semitone). Notes are `(N, 3)` arrays
`[onset_sec, offset_sec, midi_pitch]`; MIDI is converted to Hz at the mir_eval
boundary. Metrics are defined exactly in `pipeline/eval/metrics.py`.

Python API: `evaluate_pair(audio, midi, wrapper, MetricConfig())` and
`evaluate_corpus(pairs, wrapper, config)` (micro-averaged notes, pooled onset
errors, pooled chord recall, pooled octave-error rate). Wrappers live in
`pipeline/eval/model_wrappers.py`: `GroundTruthWrapper`, `PyinBaselineWrapper`
(librosa.pyin, numpy-YIN fallback), `EngineYinWrapper` (shells out to the
engine's `yin_cli` host tool, P0.3.3), `BasicPitchWrapper` (optional). The
`pipeline eval` CLI accepts `--wrapper ground-truth|pyin|engine-yin`.

## Model bake-off status (P0.3.3, partial)

Three candidate models are scored by this harness: (a) Magenta Onsets-and-
Frames TFLite, (b) Spotify Basic Pitch, (c) the engine's C++ YIN as the floor.
This is **partial progress only** — the real-scoring bake-off (MAESTRO, DGX
captures, device phones) awaits P0.3.2 test-set assembly and data
availability. The numbers below are on the synthetic clean melody
(`make_clean_melody` in `pipeline/eval/synth.py`, 10 notes at 22050 Hz).

| wrapper | engine | synthetic F1 | onset med (s) | notes est/ref |
|---------|--------|-------------:|--------------:|--------------:|
| `engine-yin` | engine C++ YIN (yin_cli, default cfg) | 1.0000 | 0.0107 | 10/10 |
| `pyin` | librosa.pyin (threshold 0.7) | 1.0000 | 0.0067 | 10/10 |

Both floors are perfect on the clean synthetic; the engine YIN floor needed
two segmentation choices to get there, both documented in
`engine/tools/yin_cli.cpp`: (1) the offset extends by one *hop* (frame bin),
not the full analysis window, matching the pyin convention; (2) an onset
debounce of one hop — a note starts at the *confirming* frame, rejecting
single-frame transition flips (the engine's argmin-YIN confidently reports a
pitch in mixed transition windows where librosa's voiced-probability gate
stays silent). Without the debounce the engine floor scores F1 ≈ 0.5 on the
same melody, entirely from ~50 ms-early onsets, not from wrong pitches.

Attempt outcomes:

- **Engine YIN floor: RUNNING.** `engine/tools/yin_cli.cpp` (window
  2048/4096, hop, sr, confidence, min-ms, TSV or JSON), promoted a read-only
  WAV decoder into `engine/src/wav/WavReader.{h,cpp}` (built into
  `engine_core`; the test-only `test/wav_util` keeps its writer). `yin_cli`
  is a host-only executable (not installed, not in the Android build);
  `EngineYinWrapper` resolves it via `KEYQUEST_YIN_CLI` or
  `engine/build/yin_cli` relative to the repo root and fails loudly when it
  is missing.
- **Magenta Onsets-and-Frames TFLite: BLOCKED — no published TFLite artifact
  exists.** Magenta GitHub releases ship no binary assets (verified across
  all releases via the GitHub API); the magentadata GCS bucket 404s every
  plausible `onsets_frames_big_tflite` URL; Kaggle hosts no OAF model. The
  only published artifacts are a TF checkpoint (`maestro_checkpoint.zip`) and
  a TF Hub SavedModel — exporting either to TFLite requires the training
  graph and a TensorFlow install. Separately, `tflite-runtime` ships **no
  Python 3.12 wheels** (PyPI has zero cp312 builds; latest 2.14.0), so the
  interpreter cannot be installed in this venv even if a model were found.
  `OafTfliteWrapper` stays a documented skeleton until both lift.
- **Spotify Basic Pitch: BLOCKED — dependency pin.** basic-pitch 0.4.0 pins
  `tensorflow>=2.4.1,<2.15.1`; tensorflow 2.15 ships no cp312 wheels (the
  earliest Python 3.12 build is 2.16), so pip has no satisfiable resolution
  in this Python 3.12 venv. `BasicPitchWrapper` raises a descriptive
  `RuntimeError`; run it in a Python < 3.12 environment.

Real-scoring against MAESTRO is additionally blocked on data: the magentadata
GCS bucket stores no individually downloadable wavs for v3.0.0/v2.0.0 (only
65–96 GB zips/tfrecords), so `validate_maestro.py` re-runs the moment the
data is obtainable.

### Validation result (the P0.3.1 acceptance step)

Published target: Spotify Basic Pitch — note F1 ≈ 0.82, onset error ≈ 0.052 s
on MAESTRO ("A Lightweight and Real-Time ... Basic Pitch").

- **Onset-error convention: to confirm.** The 0.052 s comparison uses the
  harness's mean absolute onset error over onset-matched events, but the
  paper's exact convention (all-notes vs matched-notes, mean vs median) could
  not be verified. Re-check before unblocking the comparison
  (`pipeline/eval/validate_maestro.py`).

- **Metric correctness: PASS.** The harness reproduces mir_eval's own
  unit-test reference values exactly (10 transcription fixtures committed
  under `tests/data/mir_eval/`, F1/P/R to 1e-9), and the synthetic self-tests
  score the ground-truth oracle at F1 = 1.0 / zero onset error / zero octave
  error and the pyin floor at F1 = 1.0 (actual, on a clean 10-note synthetic
  melody; threshold is 0.8). See `tests/test_eval_harness.py` (all offline,
  ~3 s).
- **Published-number comparison: BLOCKED on two independent fronts.** The
  harness and the validation runner (`python -m pipeline.eval.validate_maestro`)
  are complete and will perform the comparison the moment both lift:
  1. **basic-pitch cannot install on Python 3.12.** basic-pitch 0.4.0 pins
     `tensorflow>=2.4.1,<2.15.1`; tensorflow 2.15 ships no cp312 wheels (the
     earliest 3.12 build is 2.16), so pip has no satisfiable resolution.
     Workaround: run the comparison in a Python < 3.12 venv.
  2. **MAESTRO audio is not individually downloadable.** The magentadata GCS
     bucket stores no individual wav objects for v3.0.0 or v2.0.0 (verified
     via `storage/v1/b/magentadata/o` — only the 65–96 GB aggregated
     `maestro-vX.Y.Z.zip` files and tfrecords). URL patterns tried, all HTTP
     404: `https://storage.googleapis.com/magentadata/datasets/maestro/v3.0.0/<audio_filename>`
     and the v2.0.0 equivalent.
- Until the blockers lift, calibration rests on the mir_eval fixture check and
  the bake-off task (P0.3.3) provides runnable published baselines.

## Determinism (§8.2.10)

Output must be byte-identical for identical input, so content diffs mean
something and CDN caching is safe. Required everywhere: sorted JSON keys,
fixed float formatting, zip entries in sorted order with timestamps zeroed,
pinned versions of every rendering tool, fixed random seeds, and `buildInfo`
(timestamp) excluded from the content hash. A CI job builds every golden
fixture twice on different machines and byte-compares.

## Stage caching (§8.2.12)

Artifacts are stored under `content/builds/<song_id>/stage-N/` (gitignored):
a 6-minute audio render is never repeated because someone fixed a fingering.
`--from-stage N` resumes from the stored intermediate without re-running the
whole pipeline. `KEYQUEST_STORE_DIR` / `KEYQUEST_BUILDS_DIR` /
`KEYQUEST_CATALOG_DIR` override the default `content/store` / `content/builds`
/ `content/catalog` for tests and CI.

## Dev

```bash
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"      # installs deps + pytest/hypothesis/ruff
pytest                       # tests/ (strategy in tests/README.md)
ruff check pipeline tests
```