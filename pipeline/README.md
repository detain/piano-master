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