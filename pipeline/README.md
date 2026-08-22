# KeyQuest Pipeline

Offline Python content workers that turn MusicXML/MIDI source scores into
SongPack (the app-native format) — plan §8.2. Runs standalone from the CLI and
doubles as the CMS's backend: the admin API enqueues a `job_outbox` row, a
Webman `queue-consumer` invokes the worker, and the CMS polls stage-level
progress (§8.2.12).

## Purpose

Eleven stages: ingest → validate → normalize → hands+fingering → chunking →
difficulty → layout → levels → audio → pack → publish. Each stage is a pure
function `(input artifact, config) → (output artifact, report)`, runnable
standalone; stage artifacts are cached by input hash so re-runs are cheap.

## CLI surface (plan §8.2)

```bash
pipeline ingest   score.musicxml --song-id fur-elise --source imslp:12345
pipeline build    fur-elise [--stage N] [--from-stage N] [--level 1,2,3]
pipeline audio    fur-elise --renderer dgx|fluidsynth [--stems all]
pipeline validate fur-elise --strict
pipeline diff     fur-elise --against published
pipeline publish  fur-elise --env staging|prod
pipeline batch    --manifest weekly-batch.yaml --parallel 4
```

Stage dispatch lands in P1; `pipeline --help` already exposes the surface.

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

Artifacts are cached by input hash: a 6-minute audio render is never repeated
because someone fixed a fingering. `--from-stage N` resumes from a stored
intermediate without re-running the whole pipeline. A killed worker mid-build
leaves no half-written pack; the job resumes or restarts cleanly.

## Dev

```bash
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"      # installs deps + pytest/hypothesis/ruff
pytest                       # tests/ (strategy in tests/README.md)
ruff check pipeline tests
```