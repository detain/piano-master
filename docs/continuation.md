# KeyQuest — Session Continuation / Handoff

Pick-up point for a fresh build-orchestrator session. Read this, plan_piano.md §20/§24,
prompt_piano.md, and the SDD ledger (.superpowers/sdd/plan_piano/progress.md) before
dispatching any work.

## Current state (2026-08-26, HEAD 1c52e85)
P1.6 lesson player IMPLEMENTED — 4 commits on master, push to origin/master + CI
confirmation pending (master is ahead 4): `0b28af0` (D1 RealtimeScorer), `5226130` (D2
SongPack model/loader + LayoutHintDeriver + ProtoScoreAdapter + LessonSession +
ComboTracker), `1ca619b` (D3 LessonPlayerScreen + OnScreenKeyboard + NoteVoice seam +
renderer feedback), `1c52e85` (D4 Paparazzi 2.0.0-alpha05, 4 goldens, CI +=
`:app:verifyPaparazziDebug`). 218 tests green locally: 134 scoring (LINE 99.52%) + 84 app
incl. 4 screenshots.

## What shipped (compact; full detail in plan §24 2026-08-26 entry)
- D1 — RealtimeScorer (0b28af0), in `:scoring`: incremental driver over the batch
  `Scorer`; freeze rule watermark > `runningMaxClose[k]` (prefix freeze, never flips);
  tentative verdicts = full batch given events so far; `finalize()` == batch EXACTLY;
  delivery contract (monotone `onTimeNs`, tick after all events ≤ now; frozenScore NOT
  monotone — documented); tempo-inversion regression test (60→240 bpm at beat 8 —
  running-max rule, not per-note close); last-ulp boundary tests; property suite 200
  seeds × 8 scenarios incl. frozen==batch-now gate. 134 tests, LINE 99.52%, jacoco ≥0.95
  gate green.
- D2 — data/session layer (5226130): SongPack v1 model (`com.keyquest.app.songpack`) +
  org.json loader (format gate `songpack/v1`, strict required fields, spec ranges incl.
  denominator set, strictly-increasing tempoMap) + LayoutHintDeriver (pitch-rank lanes
  %5 per hand, per-staff ≤0.5-beat beam runs, xHint = startBeat*1000) + ProtoScoreAdapter
  (chunk filter [startBeat,endBeat), rebased renderer beats + xHint, tieToIndex
  pack→chunk-local remap, cross-chunk → null, tempo fallback, absolute-beat expected
  notes) + LessonSession (READY/PLAYING/PAUSED/FINISHED; frame-clock anchored pass clock;
  loop = reset-and-replay with re-anchor; retry rebuilds scorer; combo advances on event
  AND clock freezes in freeze order) + ComboTracker. 72 app tests.
- D3 — lesson player UI (1ca619b): LessonPlayerScreen (virtual frame clock — single
  source of truth, freezes while paused; pass-relative renderer beats + keyboard target
  window; per-frame NoteFeedback arrays zero-alloc; transport play/pause/loop/tempo/
  progress/skin toggle/reduced motion/combo readout; results overlay stars/score/heatmap/
  retry/next; fail-loud asset load) + OnScreenKeyboard (multi-touch `awaitEachGesture`,
  boundary-centered black keys, ~1-beat target glow, wrong-key flash, KeyboardLayout pure
  math) + NoteVoice seam (SilentVoice) + renderer feedback wiring (verdict colors, hit pop
  0.25-beat decay, staff color-only) + `externalSongTimeBeats` + `pickup_anacrusis`
  bundled as asset + MainActivity → LessonPlayerScreen. 80 app tests.
- D4 — Paparazzi screenshots + CI (1c52e85): Paparazzi 2.0.0-alpha05, 4 goldens vs the
  `pickup_anacrusis` fixture (both skins w/ feedback, keyboard targets, results overlay —
  deterministic component-level; the full screen has async asset loading + frame loop).
  CI android-unit += `:app:verifyPaparazziDebug`. 84 tests green locally (80 unit + 4
  screenshots); goldens recorded on this Linux/JDK21 server.

## Build workarounds (MUST stay documented — do not "clean up")
- Root buildscript: force sdk-common 31.13.2 → 31.9.1 (31.13.2 REMOVED
  `GradleVersion.parse`, breaking AGP 8.9.1 `VersionCheckPlugin`).
- App module: force kotlin-stdlib 2.2.0 (paparazzi transitives resolve 2.3.0; compiler is
  2.2.0).
- Renderer testability seams: `layoutDispatcher` (Unconfined in tests) +
  `fixedViewportSize` (Paparazzi never fires `onSizeChanged` — verified empirically;
  `produceState` keys include `fixedViewportSize`).
- Kotlin 2.2 REMOVED `InputStream.readText()` — use
  `bufferedReader().use { it.readText() }`.

## Agent-dispatch lesson (CRITICAL — read before dispatching ANYTHING)
- Write-capable agents (coder, general) return EMPTY results on long one-shot specs
  (~5–6 KB prompts): task reports "completed", zero files created.
- A ~600-char sanity task worked instantly (Touchstone.kt).
- Long task() calls can also drop required JSON keys (description/prompt/subagent_type).
- RULE: one small step per dispatch (one file, prompt ≤ ~1500 chars); verify via ls/mtime;
  resume an empty-result task via task_id once, then re-dispatch smaller. Never one-shot
  a multi-file spec. (This rule cost a full session to learn — follow it.)

## Next work (in order; small dispatches)
1. Push to origin/master + confirm CI 5/5 green (first CI run also proves cross-machine
   Paparazzi goldens).
2. Hardware-gated (when the 5 phones / DGX-520 / MIDI keyboards arrive): P0.4 MIDI
   (USB+BLE), P0.7 DGX ground-truth corpus, P0.2.4 latency rig, P0.3.4 device model
   bench, P0.5.3 fps measurement (≥58fps avg, JankTracker logcat tag KeyQuestJank).
3. P0.3 bake-off unblock: Python 3.11 venv (basic-pitch needs TF<2.15.1; no cp312
   wheels), OAF TFLite, MAESTRO audio → run validate_maestro.py → P0.3.6 model ADR.
4. Human/legal: P0.8.2 per-song checklist (15 candidates), P0.8.4 legal sign-off, P0.6.3
   8h overnight idle soak.
5. P0.9 gate re-issue (docs/phase-gates/P0.9-gate-review.md) with measured numbers.

P1.6 open items: P1.8.4 tempo control (multi-point tempo renderer mapping); P1.8 mic/MIDI
clock alignment (`NoteEvent.onTimeNs` must be on the frame clock — TOUCH only today);
loop-replay detection via frozenCount regression (latent zero-note chunk); in-lesson
score readout cut.

## Open items / known gaps
- P1.1 SongPack v1 is frozen — format must not change without an ADR + golden-fixture
  migration (plan §20 P1.1 expectation).
- Pipeline v0 scope cuts (docs/specs/pipeline-v0.md): calibrated difficulty, L2/L3
  generation, fingering, D.S./D.C. rejection, DGX renderer, CDN publish — all deferred;
  fluidsynth backend awaits provisioning (apt needs sudo).
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
- Scoring calibration open questions (docs/specs/scoring-v1.md): perfectBand / matching-window
  values to be tuned against P0.2.4 device latency data when it lands; the :scoring `replay`
  tool exists so scoring changes can be argued with recorded sessions.
- P0.6.3 8h overnight idle reconnect test still pending.
- ADR-0003 (notation renderer decision) pending device fps/latency numbers (P0.5.3).

## Environment (server 2026-08-22)
Ubuntu 24.04.4, 64-core/251GB, 3.3TB disk. PHP 8.3.6+Composer 2.10.1, Node 24/npm 11,
Java 21, cmake 3.28.3 (/usr/bin; broken pip shims removed), g++ 13/ninja, ffmpeg 6.1.1,
k6 v2.2.0 (~/bin). Python 3.13 base; pipeline .venv = Python 3.12.12 (from conda ai env
/home/my/miniconda3/envs/ai). Android SDK /home/my/android-sdk (platform 36, build-tools
34/35/36, NDK 27.3.13750724). gh authed as detain. Remote git@github.com:detain/piano-master.
NOTE: record Paparazzi goldens on THIS server (Linux, JDK 21 — matches CI ubuntu-latest).

## Discipline (non-negotiable)
- Review loop after EVERY task: reviewer agent → fix until APPROVE.
- Trunk-based; commits on master; CI (5 jobs: engine-host-tests, android-unit, api-tests,
  cms-build, lint-all) is the gate; push to origin/master.
- Never run two implementers on the same workspace in parallel.
- Dispatch rule: ONE small step per agent call (prompt ≤ ~1500 chars); verify via ls/mtime.
- Maintain the ledger + plan §24 + docs/continuation.md + prompt_piano.md as work lands.
