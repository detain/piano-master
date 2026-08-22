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
(librosa.pyin, numpy-YIN fallback), `BasicPitchWrapper` (optional).

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