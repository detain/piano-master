# KeyQuest — Session Continuation / Handoff

Pick-up point for a fresh build-orchestrator session. Read this, plan_piano.md §20/§24,
prompt_piano.md, and the SDD ledger (.superpowers/sdd/plan_piano/progress.md) before
dispatching any work.

## Current state (2026-08-22, HEAD f3cc1f8)
Phase 0 is functionally complete — all six Phase-0 NEXT-WORK items shipped, CI green on
every commit. The remaining Phase-0 gates are EXTERNAL (hardware / legal / time). The next
work is Phase 1's hardware-free tracks.

By workspace:
- engine/: pitch harness 88/88 (A0–C8), yin_cli, WavReader, NoteEventQueue, OboeInput
  (Android-only). Host tests green Debug+Release (-Werror).
- android/: Oboe/JNI bridge → Flow, RECORD_AUDIO, notation prototype (both skins, Bravura,
  JankStats). assembleDebug + 32 JVM tests green.
- api/: Webman skeleton, k6 baseline, reload/failover drills, ADR-0002, CacheGuard,
  MySQL reconnect fix. phpunit 23/115. Soak 23.4M req/1h, 0 err, 0 bleed.
- pipeline/: eval harness (mir_eval, pitch tolerance in cents), EngineYinWrapper,
  `pipeline eval` CLI. 9 pytest green.
- content/ + docs/: P0.8 rights groundwork (checklist + 15 candidates), P0.9 gate draft.

## Next work — Phase 1 hardware-free tracks (in order)
1. P1.1 SongPack v1 freeze — plan §8.1 + §20 P1.1. Deliverables:
   - docs/specs/songpack-v1.md (every field, units in beats, required/optional matrix,
     forward-compat rule).
   - Canonical JSON Schema at content/schema/songpack-v1.json, consumed by the Python
     pipeline, the PHP API, and Kotlin tests — one schema, three consumers, no drift
     (CI job fails on divergence).
   - 5 golden fixtures covering the awkward cases: pickup bars, mid-song key change,
     triplets, ties across chunk boundaries, 6/8, a repeat structure.
   - Versioning/migration policy (additive-only; minAppVersion gates new required behavior).
   Verify: all fixtures validate; content authored in week 4 loads unmodified at launch.
2. P1.2 Pipeline CLI v0 — plan §8.2 + §20 P1.2. Deliverables: `pipeline build` stages
   (ingest+provenance → validate [music21] → normalize [voices→hands, ties, grace,
   ornaments] → auto-chunking suggestions → layout precompute [beam groups, spacing hints,
   note-bar lanes] → audio stems [fluidsynth default, DGX later; EBU R128 −16 LUFS, Opus,
   mic-safe variant] → pack+checksum+manifest), deterministic byte-identical builds,
   bad-input corpus with actionable errors, golden-fixture CI job. Verify: same input →
   byte-identical pack on any machine; malformed MusicXML → specific error, no stack trace.
3. P1.5 Scoring engine — plan §6 + §20 P1.5. Deliverables: pure-Kotlin module (zero
   Android deps), matching windows (tempo-scaled, beginner widening), chord clustering
   (90ms cluster, partial credit), verdicts + score/stars math (remote-config thresholds),
   per-measure error telemetry, property tests (monotone score, never NaN/>100,
   deterministic, generated event streams), replay tool. Verify: ≥95% line coverage,
   property suite green.

## Hardware-gated work (when the 5 phones / DGX-520 / MIDI keyboards arrive)
- P0.4 MIDI (USB host + BLE): MidiManager enumeration, running status, NoteOn-vel-0-as-off,
  source arbitration (MIDI → mic auto-disable), silent-controller → soundfont synth.
- P0.7 DGX ground-truth capture rig: aligned (audio,MIDI) pairs, sync click, automated
  alignment verification (≤5ms), batch playback, through-air captures on all 5 phones.
- P0.2.4 latency rig: loopback + high-speed camera + internal instrumentation → per-device
  budget table (<80ms mid-tier gate).
- P0.3.4 device benchmark app: each model artifact × CPU/GPU/NNAPI per device →
  latency/RTF/memory/thermal matrix.
- P0.5.3 measurement: ≥58fps avg, ≤1% dropped, no frame >24ms on the low-end device
  (JankTracker logcat tag KeyQuestJank + Perfetto); then P0.5.4 decision → ADR-0003.
- P0.3 bake-off unblock: Python 3.11 venv (basic-pitch needs TF<2.15.1; no cp312 wheels),
  obtain OAF TFLite (export from the TF checkpoint or a published artifact), MAESTRO audio
  (GCS full zips or an alternative source) → run validate_maestro.py → P0.3.6 model ADR.

## Human/legal work
- P0.8.2: fill per-song checklist records (docs/pd-verification-checklist.md) for the 15
  candidates (content/rights/candidates-2026-08.md) — classical ≈15–20 min each, folk
  ≈25–35 min.
- P0.8.4: legal sign-off on the royalty-free-only position + arrangement/recording stance.
- P0.6.3: 8h overnight idle → hit again (MySQL reconnect; use the soak driver's
  --reconnect-check or a manual burst).

## Open items / known gaps
- Docker bridge networking broken at daemon level (DOCKER-FORWARD missing; root-only fix).
  Workaround: `docker run --network host` for keyquest-mysql + keyquest-dragonfly.
  `docker compose up` keeps failing until a root user fixes dockerd.
- Auth-writes load target (≥800 req/s) NOT MET: MySQL commit fsync ~3.8ms on /dev/md0.
  Evaluate in P1: innodb_flush_log_at_trx_commit tuning, batching, separate log volume
  (async-fsync toggle proved 8.8k req/s).
- yin_cli transition-window finding: engine argmin-YIN confidently reports pitches in mixed
  transition windows — suppressed for scoring by the 1-hop debounce + min-ms; the raw stream
  needs confidence gating before a real-time UI consumes it (P0.2.2 follow-up).
- Engine G1 ragged edge (standard 2048 reads G1 a semitone flat in ~half of windows);
  production routes MIDI 21–42 through lowFreq (4096). Boundary-honesty tightening is a
  tracked follow-up (needs boundary-epsilon care so lowFreq A0 at period 1745.45 vs maxTau
  1745 is preserved).
- make bootstrap uses `python3` (3.13) for the pipeline venv; the correct .venv is 3.12.12
  (from conda ai env). Don't let bootstrap rebuild it with 3.13.
- OAF TFLite + Basic Pitch + MAESTRO audio blockers (see pipeline/README.md bake-off section).

## Environment (server 2026-08-22)
Ubuntu 24.04.4, 64-core/251GB, 3.3TB disk. PHP 8.3.6+Composer 2.10.1, Node 24/npm 11,
Java 21, cmake 3.28.3 (/usr/bin; broken pip shims removed), g++ 13/ninja, ffmpeg 6.1.1,
k6 v2.2.0 (~/bin). Python 3.13 base; pipeline .venv = Python 3.12.12 (from conda ai env
/home/my/miniconda3/envs/ai). Android SDK /home/my/android-sdk (platform 36, build-tools
34/35/36, NDK 27.3.13750724). gh authed as detain. Remote git@github.com:detain/piano-master.

## Discipline (non-negotiable)
- Review loop after EVERY task: reviewer agent → fix until APPROVE.
- Trunk-based; commits on master; CI (5 jobs: engine-host-tests, android-unit, api-tests,
  cms-build, lint-all) is the gate; push to origin/master.
- Never run two implementers on the same workspace in parallel.
- Maintain the ledger + plan §24 + docs/continuation.md + prompt_piano.md as work lands.